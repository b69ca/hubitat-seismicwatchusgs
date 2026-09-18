/*
 * Seismic Watch (USGS)
 *
 * Watches the USGS earthquake catalog around a configured location and exposes
 * new events to Hubitat automations. No API key or companion service required.
 *
 * Author: Jon Wallace
 * Copyright 2026 Jon Wallace
 * License: MIT
 * Version: 1.0.0
 */

metadata {
  definition(
    name: "Seismic Watch (USGS)",
    namespace: "jonw",
    author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-seismicwatchusgs/main/SeismicWatchUSGS.groovy"
  ) {
    capability "Sensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"

    command "clearEventHistory"
    command "testRoutineEvent"
    command "testPriorityEvent"

    attribute "watchStatus", "enum", ["initializing", "ready", "error"]
    attribute "earthquakeState", "enum", ["none", "recent", "priority"]
    attribute "eventCount", "number"
    attribute "lastChecked", "string"
    attribute "lastSuccessfulCheck", "string"
    attribute "lastError", "string"

    attribute "latestEventId", "string"
    attribute "latestMagnitude", "number"
    attribute "latestPlace", "string"
    attribute "latestEventTime", "string"
    attribute "latestEventEpoch", "number"
    attribute "latestAgeMinutes", "number"
    attribute "latestDistanceKm", "number"
    attribute "latestDirection", "string"
    attribute "latestDepthKm", "number"
    attribute "latestReviewStatus", "string"
    attribute "latestAlertLevel", "string"
    attribute "latestTsunamiFlag", "enum", ["yes", "no", "unknown"]
    attribute "latestFeltReports", "number"
    attribute "latestEventUrl", "string"

    attribute "strongestMagnitude", "number"
    attribute "strongestPlace", "string"
    attribute "strongestDistanceKm", "number"
    attribute "nearestMagnitude", "number"
    attribute "nearestPlace", "string"
    attribute "nearestDistanceKm", "number"
    attribute "eventSummary", "string"
    attribute "recentEvents", "string"

    attribute "notificationText", "string"
    attribute "notificationType", "enum", ["routine", "priority", "update", "test"]
  }

  preferences {
    input name: "setupNotes", type: "paragraph", title: "Seismic Watch 1.0.0",
      description: "Uses the hub location unless overridden. The selected latitude and longitude are sent to the USGS earthquake catalog with each request. No API key is required."
    input name: "useCustomLocation", type: "bool", title: "Use custom observer coordinates", defaultValue: false
    input name: "observerLatitude", type: "decimal", title: "Custom latitude (north positive)", range: "-90..90"
    input name: "observerLongitude", type: "decimal", title: "Custom longitude (east positive)", range: "-180..180"
    input name: "radiusKm", type: "enum", title: "Search radius", options: [
      "25":"25 km", "50":"50 km", "100":"100 km", "250":"250 km", "500":"500 km",
      "1000":"1,000 km", "2000":"2,000 km", "5000":"5,000 km"], defaultValue: "500"
    input name: "minimumMagnitude", type: "decimal", title: "Minimum magnitude", range: "-1..9.9", defaultValue: 2.5
    input name: "priorityMagnitude", type: "decimal", title: "Priority-event magnitude", range: "-1..9.9", defaultValue: 4.5
    input name: "lookbackHours", type: "enum", title: "Catalog lookback", options: [
      "1":"1 hour", "6":"6 hours", "12":"12 hours", "24":"24 hours", "48":"48 hours",
      "72":"3 days", "168":"7 days", "720":"30 days"], defaultValue: "24"
    input name: "pollMinutes", type: "enum", title: "Automatic check interval", options: [
      "5":"5 minutes", "10":"10 minutes", "15":"15 minutes", "30":"30 minutes", "60":"1 hour"], defaultValue: "10"
    input name: "maximumResults", type: "number", title: "Maximum catalog events retained", range: "10..200", defaultValue: 100
    input name: "displayedEvents", type: "number", title: "Events included in recentEvents", range: "1..20", defaultValue: 5

    input name: "alertNotes", type: "paragraph", title: "Automation events",
      description: "Button 1 is a new routine event. Button 2 is a new priority event. A priority event emits button 2 only. Test commands are clearly marked and do not change earthquakeState."
    input name: "notifyInitialHistory", type: "bool", title: "Notify for the newest event on the first successful check", defaultValue: false
    input name: "alertOnRevisions", type: "bool", title: "Notify when USGS materially revises an event magnitude", defaultValue: true
    input name: "revisionThreshold", type: "decimal", title: "Minimum magnitude revision to notify", range: "0.1..2", defaultValue: 0.3
    input name: "recentStateHours", type: "enum", title: "Keep earthquakeState recent for", options: [
      "1":"1 hour", "3":"3 hours", "6":"6 hours", "12":"12 hours", "24":"24 hours"], defaultValue: "6"
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

def installed() {
  initialize()
}

def updated() {
  initialize()
}

def uninstalled() {
  unschedule()
}

def initialize() {
  unschedule()
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  sendEvent(name: "numberOfButtons", value: 2)
  sendChanged("watchStatus", "initializing")
  configureSchedule()
  if (settings?.logEnable) runIn(1800, "logsOff")
  refresh()
}

def configureSchedule() {
  int minutes = settingNumber("pollMinutes", 10d, 5d, 60d).intValue()
  if (minutes == 60) {
    runEvery1Hour("refresh")
  } else {
    schedule("0 */${minutes} * ? * *", "refresh")
  }
}

def logsOff() {
  device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
  if (state.inFlight) {
    if (now() - ((state.requestStarted ?: 0L) as Long) < 120000L) return
    state.inFlight = null
    recordError("The previous USGS request did not complete")
  }

  try {
    Map point = observerPoint()
    String signature = filterSignature(point)
    if (state.filterSignature != signature) {
      state.filterSignature = signature
      state.snapshots = [:]
      state.hasBaseline = false
    }

    long current = now()
    long start = current - settingNumber("lookbackHours", 24d, 1d, 720d).longValue() * 3600000L
    String token = "${current}-${state.generation}"
    state.inFlight = token
    state.requestStarted = current
    sendChanged("lastChecked", localText(current))

    Map query = [
      format: "geojson",
      starttime: utcText(start),
      endtime: utcText(current + 60000L),
      latitude: compactNumber(point.latitude as Double),
      longitude: compactNumber(point.longitude as Double),
      maxradiuskm: compactNumber(settingNumber("radiusKm", 500d, 1d, 5000d)),
      minmagnitude: compactNumber(settingNumber("minimumMagnitude", 2.5d, -1d, 9.9d)),
      eventtype: "earthquake",
      orderby: "time",
      limit: settingNumber("maximumResults", 100d, 10d, 200d).intValue().toString()
    ]

    if (settings?.logEnable) {
      log.debug "Checking USGS catalog: radius ${query.maxradiuskm} km, magnitude ${query.minmagnitude}+"
    }

    asynchttpGet("catalogResponse", [
      uri: "https://earthquake.usgs.gov/fdsnws/event/1/query",
      query: query,
      contentType: "application/json",
      timeout: 20,
      headers: ["User-Agent": "Hubitat-SeismicWatch/1.0 (github.com/b69ca/hubitat-seismicwatchusgs)"]
    ], [token: token, latitude: point.latitude, longitude: point.longitude])
  } catch (Exception e) {
    state.inFlight = null
    recordError("Configuration/request: ${e.message}")
  }
}

def catalogResponse(resp, data) {
  if (!data || data.token != state.inFlight) return
  state.inFlight = null

  try {
    if (resp.hasError() || !(resp.status in [200, 204])) {
      throw new IllegalArgumentException("USGS request failed (HTTP ${resp.status})")
    }

    List features = []
    if (resp.status == 200) {
      def body = resp.getJson()
      if (!(body instanceof Map) || body.type != "FeatureCollection" || !(body.features instanceof List)) {
        throw new IllegalArgumentException("Unexpected GeoJSON response")
      }
      features = body.features
    }

    Map point = [latitude: data.latitude as Double, longitude: data.longitude as Double]
    List events = []
    features.each { feature ->
      try {
        Map event = parseFeature(feature, point)
        if (event) events << event
      } catch (Exception e) {
        log.warn "Seismic Watch: skipped malformed USGS event (${e.message})"
      }
    }
    events.sort { a, b -> (b.time as Long) <=> (a.time as Long) }

    processCatalog(events)
    state.lastSuccess = now()
    sendChanged("lastSuccessfulCheck", localText(state.lastSuccess as Long))
    sendChanged("lastError", "")
    sendChanged("watchStatus", "ready")
    if (settings?.logEnable) log.debug "USGS returned ${events.size()} qualifying earthquakes"
  } catch (Exception e) {
    recordError("Catalog response: ${e.message}")
  }
}

Map parseFeature(def feature, Map point) {
  if (!(feature instanceof Map) || feature.type != "Feature") return null
  if (!(feature.properties instanceof Map) || !(feature.geometry instanceof Map)) return null
  if (feature.geometry.type != "Point" || !(feature.geometry.coordinates instanceof List) || feature.geometry.coordinates.size() < 3) return null

  String id = safeText(feature.id, 100)
  if (!id) return null
  Map properties = feature.properties
  List coordinates = feature.geometry.coordinates
  double longitude = requiredNumber(coordinates[0], "longitude", -180d, 180d)
  double latitude = requiredNumber(coordinates[1], "latitude", -90d, 90d)
  double depth = requiredNumber(coordinates[2], "depth", -100d, 1000d)
  double magnitude = requiredNumber(properties.mag, "magnitude", -2d, 10d)
  long eventTime = requiredLong(properties.time, "event time", 0L, now() + 3600000L)
  long updated = requiredLong(properties.updated ?: properties.time, "updated time", eventTime, now() + 3600000L)
  double distance = haversine(point.latitude as Double, point.longitude as Double, latitude, longitude)
  double bearing = initialBearing(point.latitude as Double, point.longitude as Double, latitude, longitude)

  return [
    id: id,
    magnitude: magnitude,
    place: safeText(properties.place ?: "Unknown location", 180),
    time: eventTime,
    updated: updated,
    longitude: longitude,
    latitude: latitude,
    depth: depth,
    distance: distance,
    bearing: bearing,
    status: safeText(properties.status ?: "unknown", 30),
    alert: safeText(properties.alert ?: "none", 20),
    tsunami: properties.tsunami == null ? null : properties.tsunami.toString() == "1",
    felt: properties.felt == null ? 0 : Math.max(0, requiredLong(properties.felt, "felt reports", 0L, 100000000L)),
    url: safeUrl(properties.url)
  ]
}

def processCatalog(List events) {
  Map previous = state.snapshots instanceof Map ? state.snapshots : [:]
  boolean baseline = state.hasBaseline == true
  double revision = settingNumber("revisionThreshold", 0.3d, 0.1d, 2d)
  List changes = []

  events.each { event ->
    Map old = previous[event.id] instanceof Map ? previous[event.id] : null
    if (!old) {
      changes << [event: event, kind: "new"]
    } else if (settings?.alertOnRevisions != false && (event.updated as Long) > ((old.updated ?: 0L) as Long) &&
        Math.abs((event.magnitude as Double) - ((old.magnitude ?: event.magnitude) as Double)) >= revision) {
      changes << [event: event, kind: "update", oldMagnitude: old.magnitude]
    }
  }

  Map snapshots = [:]
  events.take(200).each { event ->
    snapshots[event.id] = [magnitude: event.magnitude, updated: event.updated, time: event.time]
  }
  state.snapshots = snapshots
  state.hasBaseline = true

  publishCatalog(events)

  if (!baseline && settings?.notifyInitialHistory != true) return
  if (!baseline && changes) changes = [changes.max { it.event.time as Long }]
  if (!changes) return

  double priorityMagnitude = settingNumber("priorityMagnitude", 4.5d, -1d, 9.9d)
  List priority = changes.findAll { (it.event.magnitude as Double) >= priorityMagnitude }
  Map chosen = priority ? priority.max { it.event.time as Long } : changes.max { it.event.time as Long }
  int otherCount = Math.max(0, changes.size() - 1)
  publishNotification(chosen.event as Map, chosen.kind as String, priority ? "priority" : "routine", otherCount,
    chosen.oldMagnitude == null ? null : chosen.oldMagnitude as Double)
}

def publishCatalog(List events) {
  sendChanged("eventCount", events.size())
  if (!events) {
    sendChanged("earthquakeState", "none")
    clearEventAttributes()
    sendChanged("eventSummary", "No qualifying earthquakes in the configured area and lookback interval.")
    sendChanged("recentEvents", "")
    return
  }

  Map latest = events.max { it.time as Long }
  Map strongest = events.max { it.magnitude as Double }
  Map nearest = events.min { it.distance as Double }
  long ageMinutes = Math.max(0L, (long)((now() - (latest.time as Long)) / 60000L))
  long recentMinutes = settingNumber("recentStateHours", 6d, 1d, 24d).longValue() * 60L
  double priorityMagnitude = settingNumber("priorityMagnitude", 4.5d, -1d, 9.9d)
  List recent = events.findAll { now() - (it.time as Long) <= recentMinutes * 60000L }

  sendChanged("earthquakeState", !recent ? "none" :
    recent.any { (it.magnitude as Double) >= priorityMagnitude } ? "priority" : "recent")
  publishLatest(latest, ageMinutes)
  sendChanged("strongestMagnitude", rounded(strongest.magnitude as Double, 1))
  sendChanged("strongestPlace", strongest.place)
  sendChanged("strongestDistanceKm", rounded(strongest.distance as Double, 1))
  sendChanged("nearestMagnitude", rounded(nearest.magnitude as Double, 1))
  sendChanged("nearestPlace", nearest.place)
  sendChanged("nearestDistanceKm", rounded(nearest.distance as Double, 1))
  sendChanged("eventSummary", describeEvent(latest))
  int count = settingNumber("displayedEvents", 5d, 1d, 20d).intValue()
  sendChanged("recentEvents", events.take(count).collect { describeEvent(it) }.join("\n").take(8000))
}

def publishLatest(Map event, long ageMinutes) {
  sendChanged("latestEventId", event.id)
  sendChanged("latestMagnitude", rounded(event.magnitude as Double, 1))
  sendChanged("latestPlace", event.place)
  sendChanged("latestEventTime", localText(event.time as Long))
  sendChanged("latestEventEpoch", (long)((event.time as Long) / 1000L))
  sendChanged("latestAgeMinutes", ageMinutes)
  sendChanged("latestDistanceKm", rounded(event.distance as Double, 1))
  sendChanged("latestDirection", compass(event.bearing as Double))
  sendChanged("latestDepthKm", rounded(event.depth as Double, 1))
  sendChanged("latestReviewStatus", event.status)
  sendChanged("latestAlertLevel", event.alert)
  sendChanged("latestTsunamiFlag", event.tsunami == null ? "unknown" : event.tsunami ? "yes" : "no")
  sendChanged("latestFeltReports", event.felt)
  sendChanged("latestEventUrl", event.url)
}

def clearEventAttributes() {
  ["latestEventId", "latestPlace", "latestEventTime", "latestDirection", "latestReviewStatus",
   "latestAlertLevel", "latestEventUrl", "strongestPlace", "nearestPlace"].each { sendChanged(it, "") }
  ["latestMagnitude", "latestEventEpoch", "latestAgeMinutes", "latestDistanceKm", "latestDepthKm",
   "latestFeltReports", "strongestMagnitude", "strongestDistanceKm", "nearestMagnitude", "nearestDistanceKm"].each {
    sendChanged(it, 0)
  }
  sendChanged("latestTsunamiFlag", "unknown")
}

def publishNotification(Map event, String kind, String level, int otherCount, Double oldMagnitude) {
  String prefix = kind == "update" ? "USGS revised" : level == "priority" ? "Priority earthquake" : "New earthquake"
  String change = oldMagnitude == null ? "" : " (was M${rounded(oldMagnitude, 1)})"
  String extras = otherCount > 0 ? " ${otherCount} other new or revised event${otherCount == 1 ? '' : 's'} also found." : ""
  String text = "${prefix}: M${rounded(event.magnitude as Double, 1)}${change}, ${rounded(event.distance as Double, 1)} km ${compass(event.bearing as Double)}; ${event.place}; ${localText(event.time as Long)}.${extras}"
  sendEvent(name: "notificationText", value: text.take(1000), isStateChange: true)
  sendEvent(name: "notificationType", value: kind == "update" ? "update" : level, isStateChange: true)
  push(level == "priority" ? 2 : 1)
}

def clearEventHistory() {
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.snapshots = [:]
  state.hasBaseline = false
  sendChanged("watchStatus", "initializing")
  refresh()
}

def testRoutineEvent() {
  testNotification(1, "routine")
}

def testPriorityEvent() {
  testNotification(2, "priority")
}

def testNotification(int button, String kind) {
  sendEvent(name: "notificationText", value: "TEST: Seismic Watch ${kind} event. No real earthquake is implied.", isStateChange: true)
  sendEvent(name: "notificationType", value: "test", isStateChange: true)
  push(button)
}

def push(button) {
  int number = button as Integer
  if (number in [1, 2]) {
    sendEvent(name: "pushed", value: number, isStateChange: true, type: "digital")
  }
}

Map observerPoint() {
  def latitude = settings?.useCustomLocation ? settings?.observerLatitude : location?.latitude
  def longitude = settings?.useCustomLocation ? settings?.observerLongitude : location?.longitude
  return [
    latitude: requiredNumber(latitude, "latitude", -90d, 90d),
    longitude: requiredNumber(longitude, "longitude", -180d, 180d)
  ]
}

String filterSignature(Map point) {
  return [
    compactNumber(point.latitude as Double),
    compactNumber(point.longitude as Double),
    compactNumber(settingNumber("radiusKm", 500d, 1d, 5000d)),
    compactNumber(settingNumber("minimumMagnitude", 2.5d, -1d, 9.9d)),
    settingNumber("lookbackHours", 24d, 1d, 720d).intValue()
  ].join("|")
}

double haversine(double lat1, double lon1, double lat2, double lon2) {
  double radians = Math.PI / 180d
  double aLat = lat1 * radians
  double bLat = lat2 * radians
  double dLat = (lat2 - lat1) * radians
  double dLon = (lon2 - lon1) * radians
  double a = Math.sin(dLat / 2d) * Math.sin(dLat / 2d) +
    Math.cos(aLat) * Math.cos(bLat) * Math.sin(dLon / 2d) * Math.sin(dLon / 2d)
  return 6371.0088d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0d, 1d - a)))
}

double initialBearing(double lat1, double lon1, double lat2, double lon2) {
  double radians = Math.PI / 180d
  double aLat = lat1 * radians
  double bLat = lat2 * radians
  double dLon = (lon2 - lon1) * radians
  double y = Math.sin(dLon) * Math.cos(bLat)
  double x = Math.cos(aLat) * Math.sin(bLat) - Math.sin(aLat) * Math.cos(bLat) * Math.cos(dLon)
  double degrees = Math.atan2(y, x) / radians
  return (degrees + 360d) % 360d
}

String describeEvent(Map event) {
  return "M${rounded(event.magnitude as Double, 1)} — ${event.place}; ${rounded(event.distance as Double, 1)} km ${compass(event.bearing as Double)}; depth ${rounded(event.depth as Double, 1)} km; ${localText(event.time as Long)}."
}

String compass(double bearing) {
  List points = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]
  return points[((int)Math.floor((bearing + 11.25d) / 22.5d)) % 16]
}

double settingNumber(String key, double fallback, double low, double high) {
  def raw = settings?.get(key)
  double value = raw == null ? fallback : Double.parseDouble(raw.toString())
  if (Double.isNaN(value) || Double.isInfinite(value) || value < low || value > high) {
    throw new IllegalArgumentException("${key} must be ${low} to ${high}")
  }
  return value
}

double requiredNumber(def raw, String name, double low, double high) {
  if (raw == null) throw new IllegalArgumentException("Missing ${name}")
  double value = Double.parseDouble(raw.toString())
  if (Double.isNaN(value) || Double.isInfinite(value) || value < low || value > high) {
    throw new IllegalArgumentException("Invalid ${name}")
  }
  return value
}

long requiredLong(def raw, String name, long low, long high) {
  if (raw == null) throw new IllegalArgumentException("Missing ${name}")
  long value = new BigDecimal(raw.toString()).longValue()
  if (value < low || value > high) throw new IllegalArgumentException("Invalid ${name}")
  return value
}

String safeText(def raw, int maximum) {
  if (raw == null) return ""
  return raw.toString().replaceAll(/[\p{Cntrl}]/, " ").replaceAll(/\s+/, " ").trim().take(maximum)
}

String safeUrl(def raw) {
  String value = safeText(raw, 500)
  return value ==~ /https:\/\/earthquake\.usgs\.gov\/.+/ ? value : ""
}

String compactNumber(double value) {
  return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

def rounded(double value, int places) {
  return new BigDecimal(Double.toString(value)).setScale(places, BigDecimal.ROUND_HALF_UP)
}

TimeZone hubTimezone() {
  return location?.timeZone ?: TimeZone.getTimeZone("UTC")
}

String localText(long time) {
  return new Date(time).format("yyyy-MM-dd HH:mm:ss z", hubTimezone())
}

String utcText(long time) {
  return new Date(time).format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
}

def sendChanged(String name, value) {
  if (device.currentValue(name)?.toString() != value?.toString()) {
    sendEvent(name: name, value: value)
  }
}

def recordError(String message) {
  sendChanged("watchStatus", "error")
  sendEvent(name: "lastError", value: message.take(500))
  log.warn "Seismic Watch: ${message}"
}
