# NaviNavi — Proje Devir Notu (Claude Code için)

Bu dosya, claude.ai sohbetinde alınan kararları Claude Code'a aktarmak içindir.
Repo kök dizinine `CLAUDE.md` olarak konulursa Claude Code her oturumda otomatik okur.

## Kullanıcı ve çalışma şekli
- Kullanıcı: Özgür. **Her zaman Türkçe yanıt ver.**
- Profesyonel Android geliştiricisi değil; adımları sade ve sırayla anlat.
- APK'lar **GitHub Actions** ile derlenir. Kullanıcının bilgisayarında Android Studio kurulu olduğunu varsayma.
- Repo: `ozguryilmazz/navinavi`
- Uygulamayı **sadece kullanıcı** kullanacak, Play Store'a yüklenmeyecek (APK ile kurulum).

## ASIL AMAÇ (kullanıcı özellikle vurguladı)
Uygulamanın asıl amacı **rota üzerindeki trafik ışıklarını takip etmek**:
- İlerideki ışığın **ne kadar süre sonra kırmızı / yeşil yanacağını** söylemek.
- **Yeşili yakalamak için hız önerisi:** "Hızını ~52 km/sa'ye çıkar" ya da "~38 km/sa'ye yavaşla, yeşile denk gelirsin" gibi (GLOSA mantığı; ayrıntı "Trafik ışığı özelliği" bölümünde).
Navigasyon (harita, rota, yön tarifi) bunun altyapısı. Karar verirken bu önceliği unutma.

## Ne yapıyoruz
Araç için, **ağırlıklı olarak Android Auto** üzerinden kullanılacak kişisel bir navigasyon uygulaması:
- Hedef arama, rota çizme, adım adım yön tarifi
- Yoldan çıkınca otomatik yeniden rota
- Sesli yönlendirme **kullanıcının kendi ses kayıtlarıyla**
- Espirili (muzip) sesli anonslar
- Trafik ışığı kayıt ve "yeşile kalan süre" tahmini
- Çevrimdışı harita **yok** (her zaman internet var kabul edilir)

## Alınan teknik kararlar
| Konu | Karar | Neden |
|---|---|---|
| Dil | **Kotlin**, native Android | Android Auto (Car App Library) Flutter'da hazır değil |
| Araba ekranı | `androidx.car.app` Car App Library, kategori **NAVIGATION**, `NavigationTemplate` | Android Auto'da serbest UI yok, şablon zorunlu |
| Rota, trafik, arama | **TomTom API'leri** (Routing, Search, Traffic) | Kart istemiyor; kota dolunca engelleniyor, **ücret çıkmıyor** |
| Harita çizimi | **MapLibre Native Android** | Ücretsiz, açık kaynak; araba yüzeyine (Surface) çizilebilir |
| Harita stili | Önce ücretsiz bir stil (ör. OpenFreeMap) dene, gerekirse TomTom harita kareleri | Kota tasarrufu |
| Trafik ışığı konumları | **OpenStreetMap / Overpass API** (`highway=traffic_signals`) | Ücretsiz |
| Yerel veri | Room (SQLite) | Işık kayıtları, istatistikler |

**Reddedilenler (tekrar önerme):**
- **Google Maps / Navigation SDK:** Türkiye'de faturalandırma için 1.500 TL ön ödeme (depozito) istedi, kullanıcı istemedi.
- **Flutter:** Android Auto desteği yüzünden bırakıldı.
- **TomTom Navigation SDK:** Ücretli lisans; biz sadece ücretsiz API'leri kullanıyoruz.

## TomTom kotası (ücretsiz plan)
- Günde 50.000 harita karesi + 2.500 diğer istek (rota, arama, trafik).
- Aşılırsa HTTP 403 döner, ücret çıkmaz. Uygulama bunu düzgün yakalayıp kullanıcıya söylemeli.
- Gereksiz istekten kaçın: yeniden rota için hız sınırı koy (ör. en fazla 20 sn'de bir).

## API anahtarı — GÜVENLİK
- Anahtar **asla** koda, bu dosyaya, repoya ya da **APK'ya** yazılmaz.
- **Karar (değişti):** Repo herkese açık ve APK'lar herkese açık GitHub Release'te yayımlanıyor. Bu yüzden anahtar `BuildConfig`'e gömülmez; kullanıcı ilk açılışta uygulamaya bir kez yapıştırır, `ApiKeys` (SharedPreferences) içinde telefonda saklanır. `TOMTOM_API_KEY` Secret'ı kaldırıldı.
- APK'ya hiçbir token/anahtar gömme (GitHub token dahil).

## GitHub Actions
- Her push'ta debug APK derle, artifact olarak yükle.
- **Sabit imza anahtarı** kullan (Secret'lar: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`; alias `navinavi`), böylece yeni APK eskisinin üstüne kurulabilir, veriler silinmez. Keystore kullanıcının bilgisayarında `imza/` klasöründe (gitignore'da).
- `main` derlemeleri sabit imza varsa `b<run_number>` etiketiyle GitHub Release olarak yayımlanır. versionCode = run_number.
- **Uygulama içi güncelleme:** "Güncelle" butonu `releases/latest`'i okur, daha yeniyse APK'yı indirip yükleyiciyi açar (`Updater.kt`). Kullanıcı ilk APK'yı elle kurar, sonrakiler butonla gelir.
- Kullanıcı daha önce başka projelerde (Fetih, Relic) GitHub Actions ile APK almış; aynı mantık.

## Android Auto'da kurulum (kullanıcıya anlatılacak)
- Android Auto ayarlarında sürüm numarasına 10 kez dokunarak **geliştirici modu** aç.
- Geliştirici ayarlarında **"Bilinmeyen kaynaklar"** aç. Yoksa APK'dan kurulan uygulama arabada görünmez.

## Araba ekranına harita çizimi
- `NavigationTemplate` + `SurfaceCallback` ile araba yüzeyi alınır.
- MapLibre'yi bu yüzeye çizmek için bilinen yöntem: yüzeyden `VirtualDisplay` oluşturup üzerinde `Presentation` içinde `MapView` göstermek. Önce küçük bir deneme (spike) ile doğrula, sonra devam et.
- Telefon ekranı ikincil; telefonda da harita ve hedef arama olsun.

## Rota ve yön tarifi
- TomTom Routing API: `traffic=true`, `instructionsType=tagged`, `language=tr-TR`, `routeRepresentation=polyline`, `sectionType=traffic`.
- TomTom Search API: hedef arama, `countrySet=TR`, `language=tr-TR`.
- Konumu rota çizgisine oturt (map matching basit hali: en yakın segmente izdüşüm).
- Rotadan ~50 m uzaklaşma birkaç saniye sürerse yeniden rota iste.
- Sıradaki manevra, mesafe ve ok ikonu araba ekranında `NavigationTemplate` üzerinde gösterilir.

## Ses sistemi (kullanıcının kendi sesi)
- Kullanıcı kısa ses parçaları kaydedecek; uygulama bunları **birleştirerek** çalacak.
  Örnek: `"200 metre sonra"` + `"sağa dönün"`, `"Dönel kavşaktan"` + `"ikinci çıkıştan"` + `"çıkın"`.
- Sesler `assets/voice/` altında, hangi dosyanın ne olduğunu tanımlayan bir `voice_manifest.json` ile.
- Google/Android TTS **kullanılmayacak**.
- Ses çalarken radyo/müziği kısmak için audio focus (ducking) kullan.
- Muzip anonslar için her olayda birden fazla varyant; rastgele seçilsin, art arda aynısı gelmesin.
- **Henüz yapılacak:** Kullanıcı için kaydedilecek tüm cümlelerin listesi (dosya adlarıyla birlikte). Ses dosyaları hazır olana kadar geçici olarak sessiz/bip ya da yer tutucu kullan.

### Ses parçası grupları (taslak)
- Mesafe: 50 m, 100 m, 200 m, 300 m, 500 m, 1 km, 2 km sonra; "şimdi"
- Manevra: sağa / sola / hafif sağa / hafif sola / keskin sağa / keskin sola dönün, düz devam edin, U dönüşü yapın
- Kavşak: "Dönel kavşaktan" + 1.–5. çıkış + "çıkın"
- Durum: rota hesaplanıyor, rota yeniden hesaplanıyor, hedefe ulaştınız, GPS sinyali yok
- Muzip olaylar (aşağıda)

## Muzip özellikler (sadece sesli, ekrana dokunma gerektirmez)
Kullanıcı "espirili ses kayıtları olur" dedi. Öneri listesi:
- Yoldan çıkınca: "Yine mi kendi bildiğini yaptın?" tarzı rastgele cümleler; üst üste 3. seferde ayrı bir cümle
- Uzun süre dönüşsüz düz yol: "Uyuma, hâlâ buradayım"
- Trafikte uzun duruş
- Varışta espirili cümle, eve varınca özel cümle
- Kullanıcının belirlediği hız eşiği aşılınca uyarı
- Saate göre selam (sabah / gece)
- Trafik ışığı tahmini tutunca / tutmayınca
- İstatistik ve rozetler (toplam km, yanlış dönüş rekoru vb.) — sonraki aşama
- Araç ikonu seçimi (araba, traktör, tavuk, yumurta) — sonraki aşama

## Trafik ışığı özelliği
**Kaynak veri:** Türkiye'de ışık sürelerini veren açık bir servis yok. Süre, kullanıcının kendi kayıtlarından öğrenilecek.

**Gösterim:**
- Rota çizilince rota üzerindeki ışıklar Overpass API'den çekilir (rota çizgisine ~20 m yakın olanlar).
- Bir ışığa **200 m** kalınca ekranda ışık ikonu ve mesafe belirir.

**Kayıt (kullanıcının fikri):**
- Ekranda büyük **🟢 Yeşil** ve **🔴 Kırmızı** butonları. Yeşil yandığı an yeşile, kırmızıya döndüğü an kırmızıya basar.
- Basıldığı an **saniye hassasiyetinde** kaydedilir (GPS saati tercih edilir: `Location.getTime()`).
- Android Auto'da butonlar `NavigationTemplate` action strip'inde, tek dokunuşla. Kullanıcı hareket halinde de basabilmeli (klima/radyo düğmesi gibi tek dokunuş). Menü, onay penceresi yok.
- Telefon ekranında da aynı butonlar.
- Hibrit: Butona basılmazsa araç dur-kalk anlarından **otomatik, düşük güvenilirlikte** kayıt alınır. Kullanıcı kayıtları "kesin", otomatikler "yaklaşık".
- İleride: Bluetooth kumanda (selfie kumandası) ile tek tık yeşil, çift tık kırmızı.

**Tahmin:**
- Her ışık **ayrı** öğrenilir; döngü süresi ışıktan ışığa değişir.
- Yeşil başlangıç anları arasındaki farklar döngünün katlarıdır. 40–180 sn aralığında, farkları en iyi açıklayan döngü süresi aranır (toleranslı ortak bölen mantığı).
- Kırmızı kayıtları varsa yeşil süresi de hesaplanır.
- En az 3–4 kayıt olmadan tahmin gösterme.
- Kayıtları **saat dilimine göre** ayır (sabah / gün / akşam / gece programları farklı olabilir).
- Tutarsız ışık (akıllı/değişken kavşak) "değişken" işaretlenir, tahmin gösterilmez.
- Ekranda her zaman **"Tahmini yeşil: ~23 sn"** gibi "tahmini" ibaresiyle göster.

**Hız önerisi (yeşili yakalama):**
- Işığa kalan mesafe (rota üzerinden, `Navigator.traveled` ile) ve tahmini yeşil penceresi [yeşil başlangıç, yeşil bitiş] bilinir.
- Mevcut hızla varış anı yeşil penceredeyse: "Bu hızla yeşile denk geliyorsun".
- Değilse, pencereye denk gelen hız aralığı = mesafe / pencere süreleri. Bu aralık **yasal hız sınırı** (TomTom rota yanıtındaki hız sınırı ya da OSM `maxspeed`) ile makul bir alt sınır (ör. 20 km/sa) arasında kalıyorsa öner: "~52 km/sa'ye çık" / "~38 km/sa'ye yavaşla".
- **Asla hız sınırının üstünde hız önerme.** Hiçbir yasal hız yeşili yakalatmıyorsa "Kırmızıya denk geleceksin, ~25 sn bekleme" de.
- Tahmin güveni düşükse ("yaklaşık" kayıtlar, az veri, "değişken" ışık) hız önerisi verme, sadece süreyi "tahmini" ile göster.
- Öneri sesli de söylenecek (6. adımdaki kendi ses kayıtlarıyla); sürücüyü sık sık rahatsız etmemek için aynı ışık için en fazla 1–2 kez.

## Yol haritası
1. **Proje iskeleti:** Kotlin Android projesi, Gradle, `.gitignore`, uygulamada girilen anahtar, GitHub Actions ile imzalı APK
2. Telefonda MapLibre haritası + anlık konum
3. TomTom ile hedef arama ve rota çizme
4. Adım adım yön tarifi, rotadan çıkınca yeniden rota
5. **Android Auto:** Car App Library, harita yüzeyi, `NavigationTemplate`, sıradaki manevra
6. Ses sistemi + kayıt listesi + muzip anonslar
7. Trafik ışığı: konum çekme, 🟢/🔴 kayıt, tahmin, **hız önerisi**
8. İstatistik, rozetler, araç ikonları

## Durum (2026-11-06 itibarıyla)
- **1–4. adımlar tamam**; rota ve yön tarifi kullanıcı tarafından araçta denendi. Son sürüm b12'deki ok (pusula/GPS) düzeltmesi henüz denenmedi. Yapılanlar:
  - İmzalı APK + `b<run>` GitHub Release; uygulama içi **Güncelle** butonu (`Updater.kt`).
  - TomTom anahtarı uygulamada girilir (`ApiKeys.kt`), APK'da gizli bilgi yok.
  - MapLibre + OpenFreeMap (`liberty` stili), konum; ekran dönünce kamera korunur (`configChanges`).
  - Yazdıkça arama (typeahead, 400 ms bekleme, önbellek), isimle eşleşmeyenler elenir (`PlaceMatcher.kt`).
  - **Google Haritalar'dan Paylaş → NaviNavi** (`GoogleMapsLink.kt`): kısa bağlantının yönlendirmesindeki `!3d..!4d..` koordinatı okunur. Bazı bağlantılarda koordinat yok; o zaman adres TomTom geocode ile bulunup sadece bölge gösterilir (kırsalda çok uzak çıkabiliyor, rota çizilmez).
  - **Favoriler** (`Favorites.kt`): rotadaki yeri ★ Kaydet; yeşil konum butonuna uzun basınca bulunduğun yeri kaydet; aramada en üstte.
  - Adım adım yön tarifi (`Navigator.kt`): rotaya izdüşüm, sıradaki manevra, kalan yol/süre/varış; 50 m + 4 sn sapmada yeniden rota (en fazla 20 sn'de bir). Ok: dururken pusula, ~9 km/sa üstünde GPS yönü.
- **Reddedilen ek kaynaklar:** Yandex (ücretsiz şartlar navigasyon benzeri uygulamayı, sonuçları süzmeyi ve 30 günden uzun saklamayı yasaklıyor; Places API ücretli). TomTom ve OSM'de küçük işletmeler eksik; çözüm Google paylaşımı + favoriler.
- **5. adım kodlandı (2026-11-07, `android-auto` dalı), araçta henüz denenmedi:**
  - `NavSession.kt`: telefon ve arabanın ortak navigasyon durumu (StateFlow); rota, ilerleme, yeniden rota burada.
  - `NavigationService.kt`: konum tipinde ön plan servisi; arka planda/ekran kapalıyken navigasyon sürer.
  - `car/`: `NaviCarAppService` + `NavScreen` (NavigationTemplate, manevra oku, varış tahmini), `DestinationScreen`/`SearchScreen` (favoriler, arama sadece "Ara"ya basınca), `CarMapRenderer` (VirtualDisplay + Presentation + MapLibre textureMode), `CarGuidance` (TomTom manevra → Car `Maneuver`, ok ikonları `res/drawable/ic_turn_*`).
  - Araba haritası çizimi (VirtualDisplay yöntemi) gerçek araçta doğrulanmalı.
- 5. adım main'de yayımlandı (b16). Kullanıcı Android Auto'da "Bilinmeyen kaynaklar"ı açtı; araç denemesini en sona bıraktı.
- **7. adım, 1. kısım (veri toplama) kodlandı (`trafik-isigi` dalı):**
  - `TrafficLights.kt`: Overpass POST (`node(around:25,<çizgi>)[highway=traffic_signals]`), yedek sunucu `overpass.private.coffee`. GET isteği 406 veriyor, POST şart. Rota ilk 60 km, Douglas–Peucker 8 m. Aynı kavşakta 40 m içindeki düğümler tek ışık.
  - Işık anahtarı `RouteLight.key` = `<osm düğüm id>:<yön dilimi 0-3>`.
  - `LightStore.kt`: düz SQLite (`lights.db`, tablo `records`). Room yerine; KSP eklentisi yerel derleme olmadan riskli.
  - `NavSession.recordLight()`: en yakın ışık (−40…+250 m), GPS saati. `autoRecord()`: ışığa 30 m içinde ≥5 sn duruş → kalkışta AUTO yeşil (−3 sn).
  - Android Auto action strip: en fazla 4 düğme, **sadece 1 yazılı** (diğerleri ikonlu) — bu kurala uy.
- **Işık düzeltmesi (b21, yayımlandı):** Overpass sunucuları çok yavaş (20–45 sn), ışıklar sessizce gelmiyordu. Artık 4 sunucuya paralel sorgu (rota boyunca 3 km'lik kutular, `out skel`, toplam 70 sn), gelen ışıklar `lights.db` → `osm_lights` tablosunda saklanır (önce saklılar gösterilir), başarısızlıkta 30 sn/1,5/3/5 dk sonra yeniden dener, ekranda `NavSession.lightsText()` durumu. Yakında ışık yokken 🟢/🔴 → bulunduğun yere **kullanıcı ışığı** (eksi düğüm id) eklenir.
- **Işık seçimi düzeltmesi (b22/b23):** 🟢/🔴 kaydı öndeki ilk ışığa (500 m'ye kadar), o yoksa 40 m geride kalana bağlanır. Haritada ışık yoksa yeni ışık sadece ~25 km/sa altındayken eklenir. Mesajda mesafe yazar ("🟢 Yeşil kaydedildi · 320 m ilerideki ışık"). Kullanıcı ışıkların telefonda yüklendiğini doğruladı.
- **7. adım 2. kısım kodlandı (`isik-tahmini` dalı, 2026-11-08), yolda henüz denenmedi:**
  - `LightPredictor.kt` (saf Kotlin, birim testli): kayıtlar geçişlere ayrılır (200 sn); 🔴→🟢 = kırmızı süresi, 🟢→🔴 = yeşil süresi, aynı renk ardışık = döngü. Ağırlıklı ortanca (AUTO ağırlığı 0,4), en az 3 ölçüm; yarısından fazlası max(4 sn, %15) sapıyorsa "değişken" → tahmin yok. Saat dilimleri: 06–10 / 10–16 / 16–20 / 20–06; dilimde veri yoksa tümü kullanılır ama güvensiz sayılır.
  - İki tahmin türü: **canlı** (bu geçişte 🔴'ye basıldı → yeşil = basış + kırmızı süresi; asıl sağlam yol) ve **program** (yeşil başlangıçları tam saniyelik döngüye oturuyorsa, R ≥ 0,93, sapma ≤ 5 sn; bilinen döngüyle ≥6 yeşil/≥3 geçiş, yoksa ≥10/≥5). Yeşil süresi bilinmiyorsa 8 sn varsayılır.
  - `SpeedAdvisor.kt`: 40–600 m arası, hareket halinde; yeşile +2 sn / −3 sn pay; 20 km/sa ile hız sınırı arası; sınır yoksa 50 km/sa. Keep / Change / Stop(bekleme).
  - Hız sınırı: TomTom `sectionType=speedLimit` (`SPEED_LIMIT`, `maxSpeedLimitInKmh`); 400 dönerse onsuz istenir. Gerçek yanıtta alan adı doğrulanmadı.
  - İsabet: basıştan önceki tahminle fark `checks` tablosunda; son ≥5 kontrolün yarısı 5 sn'den çok şaştıysa o ışıkta hız önerisi yok. Mesaj: "tahmin tuttu ✓" / "X sn şaştı".
  - Telefon üst panelde **Kayıtlar** butonu: CSV (BOM'lu UTF-8) paylaş menüsüyle. Kullanıcı dosyayı `kayitlar/` klasörüne koyar (gitignore'da).
  - Kullanıcıya anlatılacak: 🔴'ye sadece kırmızıya **döndüğünü gördüğünde** bas (zaten kırmızıysa basma); 🟢 yeşil yandığı an.
- **Sıradaki:** Kullanıcı kayıt toplasın, CSV'yi paylaşsın; gerçek veriyle eşikleri ayarla. Sonra 6. adım (ses) — hız önerisi cümleleri kayıt listesine girecek.
- Kısıt: Işık kaydı sadece navigasyon açıkken (ışıklar rotadan çekiliyor). Asıl amaç trafik ışığı (7. adım) olduğu için Android Auto'dan sonra sırayı kullanıcıyla konuş; 7. adımı 6'dan önce almak mantıklı olabilir.
- Yerel derleme yok (bilgisayarda SSL engeli); derleme sadece GitHub Actions'ta. `gh` CLI kurulu değil, durum public API ile `curl` üzerinden okunuyor.

## Claude Code'a ilk görev
1. Önce bu dosyayı repoya `CLAUDE.md` olarak ekle.
2. TomTom anahtarını kullanıcı uygulamaya kendisi girer (anahtarı sohbete yazmasını isteme).
3. 1. adımı (proje iskeleti + GitHub Actions) yap ve ilk APK'nın derlendiğini doğrula.
4. Sonra 2. adıma geç; her aşama sonunda kullanıcıya telefonda neyi test edeceğini kısaca söyle.
