package com.ozguryilmaz.navinavi.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.ozguryilmaz.navinavi.ApiKeys
import com.ozguryilmaz.navinavi.Favorites
import com.ozguryilmaz.navinavi.NavSession
import com.ozguryilmaz.navinavi.Place
import com.ozguryilmaz.navinavi.R
import com.ozguryilmaz.navinavi.TomTomApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Seçilen yere rota hesaplayıp navigasyonu hemen başlatır ve ana sayfaya döner. */
private fun Screen.goTo(renderer: CarMapRenderer, place: Place) {
    val from = renderer.currentLocation()
    if (from == null) {
        CarToast.makeText(carContext, R.string.no_location_yet, CarToast.LENGTH_LONG).show()
        return
    }
    NavSession.routeAndStart(carContext, from, place)
    screenManager.popToRoot()
}

/** Araba ekranında hedef seçimi: favoriler + "Ara". */
class DestinationScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val favs = Favorites(carContext).all()
        val list = ItemList.Builder()
        favs.forEach { p ->
            list.addItem(Row.Builder().setTitle(p.name).setOnClickListener { goTo(renderer, p) }.build())
        }
        if (favs.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.car_no_favorites))
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_destination))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .setActionStrip(
                ActionStrip.Builder().addAction(
                    Action.Builder().setTitle(carContext.getString(R.string.car_search))
                        .setOnClickListener { screenManager.push(SearchScreen(carContext, renderer)) }
                        .build()
                ).build()
            )
            .build()
    }
}

/** Araba ekranında arama. Kota için sadece "Ara" tuşuna basınca TomTom'a sorulur. */
class SearchScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {

    private val tomtom = TomTomApi(ApiKeys(carContext))
    private var results: List<Place> = emptyList()
    private var loading = false
    private var message: String? = null
    private var job: Job? = null

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) = search(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.search_hint))
            .setShowKeyboardByDefault(true)
        if (loading) {
            b.setLoading(true)
        } else {
            val list = ItemList.Builder()
            results.forEach { p ->
                val row = Row.Builder().setTitle(p.name).setOnClickListener { goTo(renderer, p) }
                if (p.address.isNotEmpty()) row.addText(p.address)
                list.addItem(row.build())
            }
            message?.let { list.setNoItemsMessage(it) }
            b.setItemList(list.build())
        }
        return b.build()
    }

    private fun search(text: String) {
        val query = text.trim()
        if (query.length < 3) return
        job?.cancel()
        loading = true
        invalidate()
        job = lifecycleScope.launch {
            try {
                val near = renderer.currentLocation()
                results = tomtom.search(query, near).take(MAX_RESULTS)
                message = if (results.isEmpty()) carContext.getString(R.string.no_results) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results = emptyList()
                message = NavSession.errorText(e)
            } finally {
                loading = false
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) invalidate()
            }
        }
    }

    private companion object {
        const val MAX_RESULTS = 6
    }
}
