package com.ozguryilmaz.navinavi

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Haritada rota çizgisini ve hedef noktasını çizer. Konum işaretinden önce eklenmeli ki onun altında kalsın. */
class RouteLayer(style: Style) {

    private val routeSource = GeoJsonSource("route")
    private val destinationSource = GeoJsonSource("destination")
    private val lightsSource = GeoJsonSource("lights")

    init {
        style.addSource(routeSource)
        style.addSource(destinationSource)
        style.addSource(lightsSource)
        style.addLayer(
            LineLayer("route-casing", "route").withProperties(
                lineColor("#0D47A1"), lineWidth(10f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addLayer(
            LineLayer("route-line", "route").withProperties(
                lineColor("#2196F3"), lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        // Rota üzerindeki trafik ışıkları: küçük turuncu noktalar.
        style.addLayer(
            CircleLayer("lights-dot", "lights").withProperties(
                circleColor("#FF9800"), circleRadius(6f),
                circleStrokeColor("#212121"), circleStrokeWidth(2f),
            )
        )
        style.addLayer(
            CircleLayer("destination-dot", "destination").withProperties(
                circleColor("#D32F2F"), circleRadius(9f),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
            )
        )
    }

    fun showDestination(p: LatLng) {
        destinationSource.setGeoJson(Point.fromLngLat(p.longitude, p.latitude))
    }

    fun showRoute(points: List<LatLng>) {
        routeSource.setGeoJson(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }))
    }

    /** Hedefi ve rotayı birlikte çizer; null olan silinir. */
    fun set(destination: LatLng?, points: List<LatLng>?) {
        if (destination != null) showDestination(destination)
        else destinationSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        if (points != null) showRoute(points)
        else routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    fun setLights(points: List<LatLng>) {
        lightsSource.setGeoJson(
            FeatureCollection.fromFeatures(
                points.map { org.maplibre.geojson.Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
            )
        )
    }

    fun clear() {
        lightsSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        routeSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        destinationSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }
}
