# Seismic Watch (USGS)

A Hubitat driver that watches the U.S. Geological Survey earthquake catalog around your home and turns new events into useful dashboard attributes and Rule Machine triggers.

There is no account, API key, subscription, companion server, or additional Hubitat library. The driver uses the official [USGS Earthquake Catalog API](https://earthquake.usgs.gov/fdsnws/event/1/) and calculates distance and direction locally.

## What it does

- Searches a configurable 25–5,000 km radius around the hub or a custom point.
- Filters by magnitude and a 1-hour to 30-day lookback.
- Separates routine events from a configurable priority magnitude.
- Detects new catalog entries without replaying the existing history after installation.
- Optionally reports material USGS magnitude revisions.
- Exposes the latest, strongest, and nearest events separately.
- Produces two standard Hubitat button events for easy automations.
- Handles empty catalogs, invalid records, timeouts, overlapping requests, and stale callbacks.

This is a catalog monitor, not an earthquake early-warning system. USGS events can appear minutes after shaking, and automatic solutions may be revised. Do not use it for life-safety actions, evacuation decisions, gas shutoffs, or tsunami warnings.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-seismicwatchusgs/main/SeismicWatchUSGS.groovy), or paste its contents, and click **Save**.
3. Open **Devices → Add Device → Virtual** and select **Seismic Watch (USGS)** from the user drivers.
4. Give the device a name such as `Seismic Watch`, open its **Preferences**, review the location disclosure and filters, then click **Save Preferences**.
5. Check the command page. `watchStatus` should move from `setup` to `ready` after the first request.

Creating the virtual device alone does not send coordinates or schedule requests. The driver remains in `setup` until you save preferences or deliberately press **Initialize**. This gives you a chance to read the disclosure and choose custom coordinates first.

The first successful check normally establishes a baseline without sending an alert. This prevents every event already inside the lookback from looking new. Enable **Notify for the newest event on the first successful check** if you want one initial notification.

To update the driver later, import the raw URL again, save, and press **Initialize** on the device.

## Privacy and network behavior

Each catalog request sends the selected latitude, longitude, radius, minimum magnitude, and time interval to `earthquake.usgs.gov`. With the default configuration, the coordinates are taken from the Hubitat location. Select **Use custom observer coordinates** if you prefer a less precise or different point.

The request contains no API key, Hubitat identifier, device name, or automation data. As with any HTTPS request, the service receives your public IP address and the driver user-agent. Results and event history are stored locally on the hub.

Checks default to every 10 minutes. A manual Refresh never starts a second request while one is in flight. The query is capped at 200 events, well below the API's 20,000-event service limit.

## Preferences

| Preference | Default | Purpose |
| --- | --- | --- |
| Use custom observer coordinates | Off | Uses custom coordinates instead of the Hubitat location. |
| Custom latitude / longitude | Empty | Decimal degrees; north/east positive and south/west negative. Zero is valid. |
| Search radius | 500 km | 25, 50, 100, 250, 500, 1,000, 2,000, or 5,000 km. |
| Minimum magnitude | 2.5 | Catalog events below this value are excluded. Magnitude may be negative for very small events. |
| Priority-event magnitude | 4.5 | New or revised events at or above this value use button 2. |
| Catalog lookback | 24 hours | 1, 6, 12, 24, 48, 72, 168, or 720 hours. |
| Automatic check interval | 10 minutes | 5, 10, 15, 30, or 60 minutes. |
| Maximum catalog events retained | 100 | Limits returned and locally remembered events from 10–200. |
| Events included in `recentEvents` | 5 | Human-readable lines from the newest 1–20 results. |
| Notify for first history | Off | On first successful check, emits one event for the newest existing result. |
| Notify on revisions | On | Watches an existing event for a material magnitude change. |
| Minimum revision | 0.3 | Magnitude difference required before a revision emits an event. |
| Keep `earthquakeState` recent for | 6 hours | State remains `recent` or `priority` while a matching event is this new. |
| Debug logging | Off | Logs query summaries; automatically turns off after 30 minutes. |

The minimum magnitude controls what the driver downloads. The priority magnitude only chooses which automation button a change emits. It is valid to set priority below the minimum, in which case every returned new event is priority.

A long lookback combined with a low minimum magnitude and large radius can exceed the configured result cap. Because USGS results are ordered newest first, older events are then omitted. Raise the magnitude, shorten the lookback, narrow the radius, or increase the cap.

## Automation events

The driver implements Hubitat's **Pushable Button** capability:

| Button | Meaning |
| --- | --- |
| 1 | A new routine event below the priority magnitude. |
| 2 | A new priority event at or above the priority magnitude. |

When several events arrive in one response, the driver emits one button event. A priority event wins over routine events. `notificationText` describes the selected event and says how many other new or revised events were found.

Magnitude revisions keep the same event ID. They use button 1 or 2 according to the revised magnitude, and `notificationType` becomes `update`. A revision smaller than the configured threshold updates the displayed attributes without emitting a button event.

Example Rule Machine setup:

1. Trigger on **Seismic Watch → button 2 pushed**.
2. Read the device's `notificationText` custom attribute into a local string variable.
3. Send that text to a notification device or announcement rule.
4. Run **Test Priority Event** from the device page. The message begins with `TEST:` and does not alter the earthquake catalog state.

Use button 1 for routine logging or a quiet notification. Use button 2 for a more prominent announcement. The test commands intentionally set `notificationType` to `test`; no real earthquake is implied.

`clearEventHistory` forgets the local deduplication baseline and immediately checks again. With the default first-history setting, the response becomes a new silent baseline. This is useful after troubleshooting; it is not normally needed.

## Attributes

| Attribute | Meaning |
| --- | --- |
| `watchStatus` | `setup` (preferences have not been accepted), `initializing`, `ready`, or `error`. |
| `earthquakeState` | `none`, `recent`, or `priority`; considers all events inside the recent-state interval. |
| `eventCount` | Number of valid results currently retained. |
| `lastChecked` | Time the most recent request started. |
| `lastSuccessfulCheck` | Time a valid response was last processed. |
| `lastError` | Most recent request, configuration, or response error; cleared after success. |
| `latestEventId` | Stable USGS catalog ID for the newest event. |
| `latestMagnitude`, `latestPlace` | Magnitude and USGS place text for the newest event. |
| `latestEventTime`, `latestEventEpoch` | Hub-local timestamp and Unix seconds for the newest event. |
| `latestAgeMinutes` | Whole minutes since the newest event's origin time. |
| `latestDistanceKm`, `latestDirection` | Great-circle surface distance and 16-point initial bearing from the configured point. |
| `latestDepthKm` | Reported hypocentral depth. Depth can be less certain than horizontal location. |
| `latestReviewStatus` | Usually `automatic` or `reviewed`, as supplied by USGS. |
| `latestAlertLevel` | PAGER level when USGS supplies one; otherwise `none`. This is not the driver's priority classification. |
| `latestTsunamiFlag` | `yes`, `no`, or `unknown`, directly reflecting the catalog flag. It is not a local warning. |
| `latestFeltReports` | Count of submitted “Did You Feel It?” responses known at the last check. |
| `latestEventUrl` | Validated HTTPS link to the USGS event page. |
| `strongestMagnitude`, `strongestPlace`, `strongestDistanceKm` | Strongest result in the current catalog window. |
| `nearestMagnitude`, `nearestPlace`, `nearestDistanceKm` | Nearest result in the current catalog window. |
| `eventSummary` | One human-readable line describing the newest result. |
| `recentEvents` | Newline-separated newest results, suitable for an Attribute dashboard tile. |
| `notificationText` | Last generated routine, priority, revision, or test message. It remains until replaced. |
| `notificationType` | `routine`, `priority`, `update`, or `test`. |
| `pushed`, `numberOfButtons` | Standard Hubitat button attributes. |

When no events qualify, event attributes are cleared or set to zero and `eventSummary` explains that the current catalog window is empty. The last notification remains available because it represents an automation event, not the current catalog.

## Interpreting the data

Magnitude is logarithmic: one whole magnitude step represents ten times the measured wave amplitude and roughly 32 times the released energy. Magnitude alone does not predict shaking at your home. Distance, depth, geology, building construction, and the type of magnitude all matter.

The distance shown here is a great-circle surface distance from the configured point to the epicenter. It does not include depth and is not a model of travel time or shaking intensity. Direction is an initial true bearing, not a magnetic compass bearing.

USGS automatically detected events may move, change magnitude, be reviewed, merge with another solution, or be deleted. The driver handles magnitude revisions but does not replay deleted-event notices. PAGER alert levels, the tsunami flag, felt reports, and review status may be absent or appear later.

For authoritative interpretation, open `latestEventUrl`. For official alerts, use the emergency-warning systems provided by your local authorities. The USGS catalog and this driver do not replace those systems.

## Errors and troubleshooting

- `watchStatus = error`: read `lastError`, verify the hub has internet access, then press **Refresh**.
- Missing location: configure the hub's latitude/longitude or enable custom coordinates.
- No events: this is a normal result. Increase radius/lookback or lower minimum magnitude if desired.
- Old event shown as latest: `latest` means newest inside the selected lookback, even if `earthquakeState` has aged to `none`.
- Repeated old notification text: `notificationText` is deliberately retained until another automation event replaces it.
- Too many small events: raise minimum magnitude, reduce radius/lookback, or reduce the number shown in `recentEvents`.

Changing the coordinates, radius, minimum magnitude, or lookback creates a fresh deduplication baseline so old results from the newly selected area are not announced as new. Other display and alert preferences preserve the history.

## Development and tests

The driver is a single Hubitat-compatible Groovy file. The tests simulate Hubitat services and do not contact USGS.

With Groovy 2.4.21 installed, run:

```sh
groovy tests/SeismicWatchUSGSTest.groovy
```

The suite covers query construction, first-run suppression, routine and priority batching, revisions, duplicate/stale callbacks, filter resets, zero coordinates, distance/bearing calculations, empty results, malformed records, URL validation, HTTP failures, and JSON-safe persisted state. GitHub Actions runs the same suite on every push and pull request.

## Data source, license, and acknowledgment

Earthquake catalog data courtesy of the [U.S. Geological Survey](https://earthquake.usgs.gov/). USGS asks automated applications that need a customized search to use its documented FDSN event service; standard real-time feeds are preferred when custom filtering is unnecessary. See the [GeoJSON summary format](https://earthquake.usgs.gov/earthquakes/feed/v1.0/geojson.php) for field definitions.

USGS-produced information is generally in the U.S. public domain, and USGS asks users to provide credit. The driver source is independently published under the MIT License; see [LICENSE](LICENSE). Seismic Watch is not affiliated with or endorsed by USGS or Hubitat.
