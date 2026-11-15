package com.example.weather.provider.openmeteo;

import com.example.weather.cache.CurrentEntry;
import com.example.weather.cache.GeocodingEntry;
import com.example.weather.cache.WeatherCache;
import com.example.weather.provider.LocationNotFoundException;
import com.example.weather.provider.WeatherProvider;
import com.example.weather.provider.WeatherProviderProperties;
import com.example.weather.provider.WeatherProviderUnavailableException;
import com.example.weather.weather.CurrentConditions;
import com.example.weather.weather.DailyForecast;
import com.example.weather.weather.Location;
import com.example.weather.weather.LocationForecast;
import com.example.weather.weather.ObservedConditions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Open-Meteo adapter (ADR 0005/0006): geocoding, then one forecast call; no retries.
 *
 * <p>Every I/O error, timeout, non-2xx status and malformed body becomes a
 * {@link WeatherProviderUnavailableException} with a fixed message and no cause, plus exactly
 * one sanitised WARN line per failed call. Query strings, user input, upstream bodies and
 * exception messages are never logged. Successful calls are not logged at INFO or above.
 *
 * <p>Results are cached through {@link WeatherCache} (ADR 0007): geocoding (including "not
 * found"), current conditions with their fetch instant, and daily forecasts. Each public call
 * opens one cache session. Upstream failures are never cached.
 */
public class OpenMeteoWeatherProvider implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class);

    static final int MAX_DAYS = 7;
    static final int MAX_BODY_BYTES = 1024 * 1024;

    static final String GEOCODING_URI = "/v1/search?name={name}&count=1&language=en&format=json";
    static final String CURRENT_URI = "/v1/forecast?latitude={lat}&longitude={lon}"
            + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,"
            + "wind_direction_10m,weather_code"
            + "&timezone=UTC&temperature_unit=celsius&wind_speed_unit=ms";
    static final String DAILY_URI = "/v1/forecast?latitude={lat}&longitude={lon}"
            + "&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code"
            + "&start_date={start}&end_date={end}"
            + "&timezone=UTC&temperature_unit=celsius&wind_speed_unit=ms";

    private final RestClient geocodingClient;
    private final RestClient forecastClient;
    private final String geocodingHost;
    private final String forecastHost;
    private final ObjectMapper objectMapper;
    private final OpenMeteoMapper mapper = new OpenMeteoMapper();
    private final WeatherCache cache;

    OpenMeteoWeatherProvider(RestClient geocodingClient, String geocodingHost,
                             RestClient forecastClient, String forecastHost, ObjectMapper objectMapper) {
        this(geocodingClient, geocodingHost, forecastClient, forecastHost, objectMapper, WeatherCache.disabled());
    }

    OpenMeteoWeatherProvider(RestClient geocodingClient, String geocodingHost,
                             RestClient forecastClient, String forecastHost, ObjectMapper objectMapper,
                             WeatherCache cache) {
        this.cache = cache;
        this.geocodingClient = geocodingClient;
        this.geocodingHost = geocodingHost;
        this.forecastClient = forecastClient;
        this.forecastHost = forecastHost;
        this.objectMapper = objectMapper;
    }

    /** Builds the adapter from validated properties (one shared JDK HttpClient). */
    public static OpenMeteoWeatherProvider create(WeatherProviderProperties properties, RestClient.Builder builder,
                                                  ObjectMapper objectMapper, WeatherCache cache) {
        WeatherProviderProperties.OpenMeteo urls = properties.openMeteo();
        JdkClientHttpRequestFactory factory =
                OpenMeteoHttp.requestFactory(properties.connectTimeout(), properties.readTimeout());
        return new OpenMeteoWeatherProvider(
                OpenMeteoHttp.client(builder, urls.geocodingBaseUrl(), factory), urls.geocodingBaseUrl().getHost(),
                OpenMeteoHttp.client(builder, urls.forecastBaseUrl(), factory), urls.forecastBaseUrl().getHost(),
                objectMapper, cache);
    }

    /** Geocoding goes through the cache; the conditions are always fetched (there is no fetch instant to store). */
    @Override
    public CurrentConditions current(String city) {
        Location location = geocode(cache.session(), city);
        return fetchCurrent(location);
    }

    @Override
    public ObservedConditions currentAt(String city, Instant now) {
        WeatherCache.Session session = cache.session();
        Location location = geocode(session, city);
        Optional<CurrentEntry> cached = session.current(location);
        if (cached.isPresent()) {
            return new ObservedConditions(cached.get().conditions(), cached.get().fetchedAt());
        }
        CurrentConditions conditions = fetchCurrent(location);
        session.putCurrent(location, conditions, now);
        return new ObservedConditions(conditions, now);
    }

    @Override
    public LocationForecast forecast(String city, LocalDate startDate, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_DAYS);
        }
        WeatherCache.Session session = cache.session();
        Location location = geocode(session, city);
        Optional<List<DailyForecast>> cached = session.forecast(location, startDate, days);
        if (cached.isPresent()) {
            return new LocationForecast(location, cached.get());
        }
        Map<String, Object> vars = new HashMap<>(coordinates(location));
        vars.put("start", startDate.toString());
        vars.put("end", startDate.plusDays(days - 1L).toString());
        LocationForecast forecast = call(UpstreamCall.FORECAST, forecastClient, forecastHost, DAILY_URI, vars,
                json -> mapper.toDaily(location, json, startDate, days));
        session.putForecast(location, startDate, days, forecast.days());
        return forecast;
    }

    private CurrentConditions fetchCurrent(Location location) {
        return call(UpstreamCall.FORECAST, forecastClient, forecastHost, CURRENT_URI, coordinates(location),
                json -> mapper.toCurrent(location, json));
    }

    /**
     * Cached geocoding: a "not found" hit is rethrown with this request's city; a fresh not-found
     * is cached negatively; an upstream failure propagates and writes nothing.
     */
    private Location geocode(WeatherCache.Session session, String city) {
        Optional<GeocodingEntry> cached = session.geocoding(city);
        if (cached.isPresent()) {
            return switch (cached.get()) {
                case GeocodingEntry.Found found -> found.location();
                case GeocodingEntry.NotFound notFound -> throw new LocationNotFoundException(city);
            };
        }
        Location location;
        try {
            location = call(UpstreamCall.GEOCODING, geocodingClient, geocodingHost, GEOCODING_URI,
                    Map.of("name", city), json -> mapper.toLocation(city, json));
        } catch (LocationNotFoundException e) {
            session.putNotFound(city);
            throw e;
        }
        session.putLocation(city, location);
        return location;
    }

    private static Map<String, Object> coordinates(Location location) {
        return Map.of(
                "lat", BigDecimal.valueOf(location.latitude()).toPlainString(),
                "lon", BigDecimal.valueOf(location.longitude()).toPlainString());
    }

    /** Raw result of an exchange: the status, and the body (only read for 2xx). */
    private record RawResponse(int status, boolean success, byte[] body, boolean tooLarge) {}

    private <T> T call(UpstreamCall call, RestClient client, String host, String template, Map<String, ?> vars,
                       Function<JsonNode, T> mapping) {
        long start = System.nanoTime();
        String outcome;
        try {
            RawResponse raw = client.get()
                    .uri(template, vars)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> read(response.getStatusCode().value(),
                            response.getStatusCode().is2xxSuccessful(), response.getBody()));
            if (raw == null || !raw.success()) {
                outcome = "status=" + (raw == null ? "unknown" : raw.status());
            } else if (raw.tooLarge()) {
                outcome = "invalid-response reason=body too large";
            } else if (raw.body().length == 0) {
                outcome = "invalid-response reason=invalid JSON";
            } else {
                JsonNode json = objectMapper.readTree(raw.body());
                if (json == null || json.isMissingNode()) {
                    outcome = "invalid-response reason=invalid JSON";
                } else {
                    return mapping.apply(json);
                }
            }
        } catch (RestClientException e) {
            outcome = "exception=" + e.getClass().getName()
                    + " cause=" + NestedExceptionUtils.getMostSpecificCause(e).getClass().getName();
        } catch (IOException e) {
            // Only objectMapper.readTree can throw here (JsonProcessingException); exchange I/O
            // errors arrive as RestClientException.
            outcome = "invalid-response reason=invalid JSON";
        } catch (InvalidUpstreamResponseException e) {
            outcome = "invalid-response reason=" + e.reason();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.warn("Upstream call failed provider=open-meteo call={} host={} path={} outcome={} elapsedMs={}",
                call.logName(), host, call.path(), outcome, elapsedMs);
        throw unavailable(call, outcome);
    }

    private static RawResponse read(int status, boolean success, InputStream body) throws IOException {
        if (!success) {
            return new RawResponse(status, false, null, false);
        }
        byte[] bytes = body == null ? new byte[0] : body.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            return new RawResponse(status, true, null, true);
        }
        return new RawResponse(status, true, bytes, false);
    }

    private static WeatherProviderUnavailableException unavailable(UpstreamCall call, String outcome) {
        return new WeatherProviderUnavailableException(
                "Open-Meteo " + call.logName() + " call failed: " + outcome, null);
    }
}
