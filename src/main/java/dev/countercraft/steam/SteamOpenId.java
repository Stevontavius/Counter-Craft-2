package dev.countercraft.steam;

import dev.countercraft.CounterCraft;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Steam's own "Sign in through Steam" (OpenID 2.0), checked the way Steam documents it. The player signs in on
 * steamcommunity.com in their own browser; CC2 never sees a password, Steam Guard code or anything else secret. Steam
 * sends back a signed statement ("this browser belongs to SteamID 7656...") which is checked here directly with Steam
 * (check_authentication), so a client can't simply claim someone else's SteamID.
 *
 * <p>Also refused: statements not from Steam's endpoint, for a return address other than the player's own computer,
 * older than five minutes, or already used once (each one links once).</p>
 */
public final class SteamOpenId {
	private static final String ENDPOINT = "https://steamcommunity.com/openid/login";
	private static final Pattern CLAIMED = Pattern.compile("^https://steamcommunity\\.com/openid/id/(7656\\d{13})$");
	private static final Pattern RETURN_TO = Pattern.compile("^http://127\\.0\\.0\\.1:\\d{1,5}/cc2/steam(\\?.*)?$");
	private static final Duration MAX_AGE = Duration.ofMinutes(5);
	private static final Set<String> USED_NONCES = ConcurrentHashMap.newKeySet();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private SteamOpenId() {
	}

	/** The SteamID64 Steam vouches for, or empty if the statement doesn't hold up (Steam says no, stale, reused, ...). */
	public static CompletableFuture<Optional<String>> verify(Map<String, String> params) {
		String claimed = params.getOrDefault("openid.claimed_id", "");
		Matcher m = CLAIMED.matcher(claimed);
		String nonce = params.getOrDefault("openid.response_nonce", "");
		if (!"id_res".equals(params.get("openid.mode")) || !ENDPOINT.equals(params.get("openid.op_endpoint")) || !m.matches()
				|| !claimed.equals(params.get("openid.identity")) || !RETURN_TO.matcher(params.getOrDefault("openid.return_to", "")).matches()
				|| !fresh(nonce) || !USED_NONCES.add(nonce)) {
			return CompletableFuture.completedFuture(Optional.empty());
		}
		String steamId = m.group(1);
		// ask Steam whether it really signed this: the same fields, mode check_authentication
		String body = params.entrySet().stream()
				.filter(e -> e.getKey().startsWith("openid."))
				.map(e -> enc(e.getKey()) + "=" + enc(e.getKey().equals("openid.mode") ? "check_authentication" : e.getValue()))
				.collect(Collectors.joining("&"));
		HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
			boolean ok = r.statusCode() == 200 && r.body().lines().anyMatch(l -> l.trim().equals("is_valid:true"));
			return ok ? Optional.of(steamId) : Optional.<String>empty();
		}).exceptionally(t -> {
			CounterCraft.LOG.warn("[countercraft] couldn't reach Steam to check a sign-in: {}", t.toString());
			return Optional.empty();
		});
	}

	/** OpenID nonces start with their UTC issue time (2026-10-06T12:00:00Z...). */
	private static boolean fresh(String nonce) {
		if (nonce.length() < 20) {
			return false;
		}
		try {
			Instant at = Instant.parse(nonce.substring(0, 20));
			Instant now = Instant.now();
			return !at.isAfter(now.plusSeconds(60)) && Duration.between(at, now).compareTo(MAX_AGE) <= 0;
		} catch (Exception e) {
			return false;
		}
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
