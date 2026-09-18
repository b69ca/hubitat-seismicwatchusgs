// Run from the repository root with Groovy 2.4.21.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def state = [:]
def settings = [:]
def readings = [:]
def events = []
def requests = []
def jobs = [:]
def warnings = []
long clock = 1789750800000L // 2026-09-18 15:00:00 UTC
def location = [latitude: 40d, longitude: -105d, timeZone: TimeZone.getTimeZone('America/Denver')]

def binding = new Binding([
  state: state,
  settings: settings,
  location: location,
  metadata: { Closure ignored -> },
  now: { -> clock },
  device: [
    currentValue: { String name -> readings[name] },
    updateSetting: { String name, Map value -> settings[name] = value.value }
  ],
  log: [warn: { message -> warnings << message.toString() }, debug: { ignored -> }, info: { ignored -> }],
  sendEvent: { Map event -> events << event; readings[event.name] = event.value },
  runIn: { seconds, handler, Map options = [:] -> jobs[handler] = [seconds: seconds, options: options] },
  unschedule: { String handler = null -> if (handler) jobs.remove(handler) else jobs.clear() },
  runEvery1Hour: { handler -> jobs[handler] = [every: 3600] },
  schedule: { expression, handler -> jobs[handler] = [cron: expression] },
  asynchttpGet: { callback, params, data -> requests << [callback: callback, params: params, data: data] }
])

def driver = new GroovyShell(binding).parse(new File('SeismicWatchUSGS.groovy'))
driver.run()

def feature = { String id, double magnitude, long time, double longitude, double latitude, Map extra = [:] ->
  [
    type: 'Feature',
    id: id,
    properties: [
      mag: magnitude,
      place: extra.place ?: "Test location ${id}",
      time: time,
      updated: extra.updated ?: time + 1000L,
      status: extra.status ?: 'reviewed',
      alert: extra.containsKey('alert') ? extra.alert : null,
      tsunami: extra.containsKey('tsunami') ? extra.tsunami : 0,
      felt: extra.containsKey('felt') ? extra.felt : 0,
      url: extra.url ?: "https://earthquake.usgs.gov/earthquakes/eventpage/${id}"
    ],
    geometry: [type: 'Point', coordinates: [longitude, latitude, extra.depth ?: 8d]]
  ]
}

def response = { List features, int status = 200 ->
  [
    hasError: { -> false },
    status: status,
    getJson: { -> [type: 'FeatureCollection', metadata: [count: features.size()], features: features] }
  ]
}

driver.installed()
assert requests.empty
assert readings.watchStatus == 'setup'
assert readings.numberOfButtons == 2
driver.initialize()
assert jobs.refresh.cron == '0 */10 * ? * *'
assert requests.size() == 1
def firstRequest = requests[-1]
assert firstRequest.params.uri == 'https://earthquake.usgs.gov/fdsnws/event/1/query'
assert firstRequest.params.query.latitude == '40'
assert firstRequest.params.query.longitude == '-105'
assert firstRequest.params.query.maxradiuskm == '500'
assert firstRequest.params.query.minmagnitude == '2.5'
assert firstRequest.params.query.eventtype == 'earthquake'
assert firstRequest.params.query.limit == '100'
assert !firstRequest.params.toString().toLowerCase().contains('apikey')

List baseline = [
  feature('us-old-1', 3.1d, clock - 3600000L, -104.5d, 40.2d, [felt: 12]),
  feature('us-old-2', 2.7d, clock - 7200000L, -106d, 39.5d)
]
driver.catalogResponse(response(baseline), firstRequest.data)
assert readings.watchStatus == 'ready'
assert readings.eventCount == 2
assert readings.latestEventId == 'us-old-1'
assert readings.latestMagnitude.toString() == '3.1'
assert readings.latestFeltReports == 12L
assert readings.latestEventUrl.endsWith('/us-old-1')
assert readings.earthquakeState == 'recent'
assert !events.any { it.name == 'pushed' }
assert state.hasBaseline
assert state.snapshots.size() == 2
println 'PASS: initialization, no-key location query, baseline suppression and catalog attributes'

clock += 600000L
driver.refresh()
def priorityRequest = requests[-1]
List withPriority = [
  feature('us-priority', 5.0d, clock - 120000L, -104.8d, 40.1d, [alert: 'green', tsunami: 1, felt: 100]),
  feature('us-routine', 3.0d, clock - 180000L, -105.4d, 40.3d),
  baseline[0], baseline[1]
]
driver.catalogResponse(response(withPriority), priorityRequest.data)
assert readings.latestEventId == 'us-priority'
assert readings.earthquakeState == 'priority'
assert readings.latestTsunamiFlag == 'yes'
assert readings.latestAlertLevel == 'green'
assert events.count { it.name == 'pushed' && it.value == 2 } == 1
assert events.count { it.name == 'pushed' && it.value == 1 } == 0
assert readings.notificationText.startsWith('Priority earthquake: M5.0')
assert readings.notificationText.contains('1 other new or revised event')
driver.catalogResponse(response(withPriority), priorityRequest.data)
assert events.count { it.name == 'pushed' } == 1 // stale callback and duplicate suppression
println 'PASS: priority classification, single batched notification, tsunami/alert attributes and stale callback rejection'

clock += 600000L
driver.refresh()
def updateRequest = requests[-1]
List revised = withPriority.collect { it }
revised[0] = feature('us-priority', 5.4d, withPriority[0].properties.time as Long, -104.8d, 40.1d,
  [updated: clock, alert: 'green', tsunami: 1, felt: 120])
driver.catalogResponse(response(revised), updateRequest.data)
assert events.count { it.name == 'pushed' && it.value == 2 } == 2
assert readings.notificationType == 'update'
assert readings.notificationText.contains('(was M5.0)')

clock += 600000L
driver.refresh()
def smallRevisionRequest = requests[-1]
revised[0] = feature('us-priority', 5.5d, withPriority[0].properties.time as Long, -104.8d, 40.1d,
  [updated: clock, alert: 'green'])
driver.catalogResponse(response(revised), smallRevisionRequest.data)
assert events.count { it.name == 'pushed' } == 2
println 'PASS: material-revision alerts and insignificant-revision suppression'

// A newer small event must not hide a recent priority event from the state.
clock += 600000L
driver.refresh()
def newestSmallRequest = requests[-1]
List newestSmall = revised + [feature('us-new-small', 2.6d, clock - 1000L, -105.1d, 40.1d)]
driver.catalogResponse(response(newestSmall), newestSmallRequest.data)
assert readings.latestEventId == 'us-new-small'
assert readings.earthquakeState == 'priority'

driver.testRoutineEvent()
assert readings.notificationType == 'test'
assert readings.notificationText.startsWith('TEST:')
assert events[-1].name == 'pushed' && events[-1].value == 1
driver.testPriorityEvent()
assert events[-1].value == 2
assert readings.earthquakeState == 'priority'

settings.useCustomLocation = true
settings.observerLatitude = 0
settings.observerLongitude = 0
settings.radiusKm = 100
clock += 600000L
driver.initialize()
assert requests[-1].params.query.latitude == '0'
assert requests[-1].params.query.longitude == '0'
assert !state.hasBaseline && state.snapshots == [:]
assert driver.haversine(0d, 0d, 0d, 1d) > 111d
assert driver.haversine(0d, 0d, 0d, 1d) < 112d
assert driver.compass(driver.initialBearing(0d, 0d, 0d, 1d)) == 'E'
println 'PASS: test events, filter reset, zero coordinates, distance and bearing calculations'

def customRequest = requests[-1]
driver.catalogResponse(response([], 204), customRequest.data)
assert readings.eventCount == 0
assert readings.earthquakeState == 'none'
assert readings.latestEventId == ''
assert readings.latestMagnitude == 0

clock += 600000L
driver.refresh()
def errorRequest = requests[-1]
driver.catalogResponse([hasError: { -> true }, status: 503], errorRequest.data)
assert readings.watchStatus == 'error'
assert readings.lastError.contains('HTTP 503')
assert readings.eventCount == 0

def parsed = driver.parseFeature(feature('safe', 3d, clock - 1000L, 1d, 1d,
  [url: 'https://example.com/not-usgs']), [latitude: 0d, longitude: 0d])
assert parsed.url == ''
assert driver.parseFeature([type: 'Feature'], [latitude: 0d, longitude: 0d]) == null
boolean failed = false
try {
  driver.parseFeature(feature('bad', Double.NaN, clock, 0d, 0d), [latitude: 0d, longitude: 0d])
} catch (Exception expected) {
  failed = true
}
assert failed

clock += 600000L
driver.refresh()
def malformedRequest = requests[-1]
driver.catalogResponse(response([
  feature('still-good', 3d, clock - 1000L, 1d, 1d),
  feature('bad-one', Double.NaN, clock - 2000L, 1d, 1d)
]), malformedRequest.data)
assert readings.watchStatus == 'ready'
assert readings.eventCount == 1
assert readings.latestEventId == 'still-good'

def restored = new JsonSlurper().parseText(JsonOutput.toJson(state))
assert restored.snapshots instanceof Map
assert JsonOutput.toJson(state).length() < 50000
println 'PASS: empty catalogs, HTTP errors, untrusted fields and JSON-safe state'
println 'ALL CHECKS PASSED'
