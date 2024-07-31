package org.carecode.lims.mw;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

public class SmartLytePlusMiddleware {

    private static JsonObject settings;

    public static void main(String[] args) {
        System.out.println("MDGPHM!");
        loadSettings();
        startServer(); // Start the listener server
    }

    public static void loadSettings() {
        try (FileReader reader = new FileReader("config.json")) {
            JsonParser parser = new JsonParser();
            settings = parser.parse(reader).getAsJsonObject();
        } catch (IOException | JsonSyntaxException e) {
            e.printStackTrace();
        }
    }

    public static void startServer() {
        try {
            String hostPort = settings.getAsJsonObject("middlewareSettings")
                                      .getAsJsonObject("analyzerDetails")
                                      .get("hostPort").getAsString();
            int port = Integer.parseInt(hostPort);

            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new ResponseHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("Server started on port " + port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ResponseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            System.out.println("Received " + method + " request from " + exchange.getRemoteAddress());

            if ("POST".equalsIgnoreCase(method)) {
                // Handle POST request
                StringBuilder requestBody = new StringBuilder();
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    int data;
                    while ((data = isr.read()) != -1) {
                        requestBody.append((char) data);
                    }
                }
                System.out.println("Request Body: " + requestBody.toString());

                // Parse request body into parameters
                Map<String, String> params = parseQueryParams(requestBody.toString());
                System.out.println("Parsed Parameters: " + params);

                // Convert to JSON
                JsonObject jsonObject = convertParamsToJson(params);
                System.out.println("Converted JSON: " + jsonObject.toString());

                // Extract Na, K, Cl, and ionized Ca values
                JsonObject ionData = jsonObject.getAsJsonObject("ionData");
                if (ionData != null) {
                    String naConc = extractIonValue(ionData, "Na");
                    String kConc = extractIonValue(ionData, "K");
                    String clConc = extractIonValue(ionData, "Cl");
                    String caConc = extractIonValue(ionData, "Ca");

                    JsonObject resultJson = new JsonObject();
                    if (naConc != null) resultJson.addProperty("Na", naConc);
                    if (kConc != null) resultJson.addProperty("K", kConc);
                    if (clConc != null) resultJson.addProperty("Cl", clConc);
                    if (caConc != null) resultJson.addProperty("Ionized_Ca", caConc);

                    sendJsonToLims(resultJson);
                }

                String responseMessage = "Response received and processed";
                exchange.sendResponseHeaders(200, responseMessage.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseMessage.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                exchange.sendResponseHeaders(405, -1); // 405 Method Not Allowed
            }
        }

        private Map<String, String> parseQueryParams(String query) throws IOException {
            Map<String, String> params = new HashMap<>();
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx != -1) {
                    params.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                }
            }
            return params;
        }

        private JsonObject convertParamsToJson(Map<String, String> params) {
            JsonObject jsonObject = new JsonObject();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                addToJson(jsonObject, key, value);
            }
            return jsonObject;
        }

        private void addToJson(JsonObject jsonObject, String key, String value) {
            int bracketIndex = key.indexOf("[");
            if (bracketIndex == -1) {
                jsonObject.addProperty(key, value);
            } else {
                String rootKey = key.substring(0, bracketIndex);
                String remainingKey = key.substring(bracketIndex + 1, key.length() - 1);
                JsonObject childObject = jsonObject.has(rootKey) ? jsonObject.getAsJsonObject(rootKey) : new JsonObject();
                addToJson(childObject, remainingKey, value);
                jsonObject.add(rootKey, childObject);
            }
        }

        private String extractIonValue(JsonObject ionData, String ionName) {
            JsonObject ion = ionData.getAsJsonObject(ionName);
            return ion != null && ion.has("conc") ? ion.get("conc").getAsString() : null;
        }

        private void sendJsonToLims(JsonObject jsonObject) {
            try {
                String limsEndpoint = settings.getAsJsonObject("middlewareSettings")
                                              .getAsJsonObject("limsSettings")
                                              .get("pushResultsEndpoint").getAsString();
                URL url = new URL(limsEndpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                System.out.println("POST Response Code to LIMS :: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    System.out.println("Data sent to LIMS successfully.");
                } else {
                    System.out.println("Failed to send data to LIMS");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
