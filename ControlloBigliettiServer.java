import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ControlloBigliettiServer {

    private static final String URL_BASE =
            "https://services.clorian.com/catalog/salesGroups/1/product/4375/availability?minTickets=1&venueId=1&year=2026&month=";

    private static final String URL = "https://tickets.sagradafamilia.org/en/1-individual/4375-sagrada-familia";

    // Stesso endpoint e chiave pubblica che il sito usa al caricamento della pagina (window.pos.apiKey)
    private static final String TOKEN_URL =
            "https://services.clorian.com/user/api/oauth/token?secretKey=thesagradafamiliafrontendoftomorrow";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final String POS = "649";

    // Su GitHub Actions l'argomento arriva dal Secret NTFY_TOPIC, così non è visibile nel repository pubblico
    private static final String NTFY_URL = "https://ntfy.sh/"
            + System.getenv().getOrDefault("NTFY_TOPIC", "sagrada-iambrosi-ola");

    private static final Set<String> DATE_DA_CONTROLLARE = Set.of(
            "2026-10-31",
            "2026-11-01",
            "2026-11-02"
    );

    private static final int INTERVALLO_SECONDI = 60;

    // GitHub Actions interrompe i job dopo 6 ore: 0 = nessun limite (VM)
    private static final long DURATA_MAX_MINUTI =
            Long.parseLong(System.getenv().getOrDefault("DURATA_MAX_MINUTI", "0"));

    private static String authorization;

    public static void main(String[] args) throws Exception {

        HttpClient client = HttpClient.newHttpClient();
        boolean erroreNotificato = false;
        // Evita di inviare la stessa notifica ogni minuto finché una data resta disponibile
        Set<String> giaNotificate = new HashSet<>();

        notifica(client, "Controllo biglietti avviato (server)", "Riceverai una notifica quando si libera una data", "min");

        long fine = DURATA_MAX_MINUTI > 0
                ? System.currentTimeMillis() + DURATA_MAX_MINUTI * 60_000
                : Long.MAX_VALUE;

        while (System.currentTimeMillis() < fine) {
            try {
                Set<String> libere = new TreeSet<>();

                // 31/10 è nel mese 10, 01-02/11 nel mese 11
                for (String month : List.of("10", "11")) {
                    if (authorization == null) {
                        authorization = ottieniToken(client);
                    }

                    HttpResponse<String> response = leggiMese(client, month);
                    if (response.statusCode() == 401) {
                        log("Token scaduto, lo rinnovo...");
                        authorization = ottieniToken(client);
                        response = leggiMese(client, month);
                    }

                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Mese " + month + " - HTTP " + response.statusCode());
                    }

                    // Formato risposta: {"2026-10-31":"no-availability","2026-11-01":"availability",...}
                    String body = response.body().replace(" ", "");
                    for (String data : DATE_DA_CONTROLLARE) {
                        if (body.contains("\"" + data + "\":\"availability\"")) {
                            libere.add(data);
                        }
                    }
                }

                log(libere.isEmpty() ? "Nessuna disponibilita'" : "DISPONIBILI: " + libere);

                Set<String> nuove = new TreeSet<>(libere);
                nuove.removeAll(giaNotificate);
                if (!nuove.isEmpty()) {
                    notifica(client, "BIGLIETTI DISPONIBILI", "Date libere: " + String.join(" ", nuove), "urgent");
                }
                giaNotificate.clear();
                giaNotificate.addAll(libere);

                if (erroreNotificato) {
                    notifica(client, "Controllo ripristinato", "Il controllo biglietti funziona di nuovo", "default");
                    erroreNotificato = false;
                }

            } catch (Exception e) {
                log("Errore: " + e);
                authorization = null;
                if (!erroreNotificato) {
                    notifica(client, "Errore controllo biglietti", String.valueOf(e.getMessage()), "high");
                    erroreNotificato = true;
                }
            }

            Thread.sleep(INTERVALLO_SECONDI * 1000L);
        }

        log("Durata massima raggiunta, termino");
    }

    private static HttpResponse<String> leggiMese(HttpClient client, String month) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL_BASE + month))
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .header("pos", POS)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String ottieniToken(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", "https://tickets.sagradafamilia.org")
                .header("Referer", "https://tickets.sagradafamilia.org/")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Matcher m = ACCESS_TOKEN.matcher(response.body());
        if (response.statusCode() != 200 || !m.find()) {
            throw new IllegalStateException("Token non ottenuto - HTTP " + response.statusCode());
        }
        log("Nuovo token ottenuto");
        return "Bearer " + m.group(1);
    }

    private static void notifica(HttpClient client, String titolo, String messaggio, String priorita) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NTFY_URL))
                    .header("Title", titolo)
                    .header("Priority", priorita)
                    .header("Click", URL)
                    .POST(HttpRequest.BodyPublishers.ofString(messaggio))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log("Notifica non inviata - HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            log("Notifica non inviata: " + e.getMessage());
        }
    }

    private static void log(String messaggio) {
        System.out.println(LocalDateTime.now().withNano(0) + " - " + messaggio);
    }
}
