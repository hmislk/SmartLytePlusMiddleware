package org.carecode.lims.mw;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import javax.swing.JOptionPane;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

public class SmartLytePlusMiddleware {

    private static JsonObject settings;

    public static void main(String[] args) {
        System.out.println("MDGPHM!");
        loadSettings();
        System.out.println(settings);
        
        startServer(); // Start the listener server
        
        while (true) {
            JsonObject sample = checkForSamples();
            if (sample != null) {
                sendTestRequestToAnalyzer(sample);
            }
            try {
                Thread.sleep(5000); // Sleep for 5 seconds before next iteration
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // Load all settings from a JSON file
    public static void loadSettings() {
        try (FileReader reader = new FileReader("config.json")) {
            JsonParser parser = new JsonParser();
            JsonElement jsonElement = parser.parse(reader);
            settings = jsonElement.getAsJsonObject();
        } catch (IOException | JsonSyntaxException e) {
            e.printStackTrace();
        }
    }

    // Check for new samples to perform
    public static JsonObject checkForSamples() {
        String input = JOptionPane.showInputDialog(null, "Enter JSON sample data or 'wait' to requery:");
        if (input == null || input.equalsIgnoreCase("wait")) {
            return null;
        } else {
            try {
                JsonParser parser = new JsonParser();
                JsonElement jsonElement = parser.parse(input);
                return jsonElement.getAsJsonObject();
            } catch (JsonSyntaxException e) {
                JOptionPane.showMessageDialog(null, "Invalid JSON input. Please try again.");
                return null;
            }
        }
    }

    // Send the test request details to the SmartLytePlus analyzer
    public static void sendTestRequestToAnalyzer(JsonObject sample) {
        try {
            JsonObject testRequest = new JsonObject();
            testRequest.add("testRequest", sample);
            
            String analyzerIP = settings.getAsJsonObject("middlewareSettings")
                                        .getAsJsonObject("analyzerDetails")
                                        .get("analyzerIP").getAsString();
            String analyzerPort = settings.getAsJsonObject("middlewareSettings")
                                          .getAsJsonObject("analyzerDetails")
                                          .get("analyzerPort").getAsString();
            String urlString = "http://" + analyzerIP + ":" + analyzerPort;
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = testRequest.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            System.out.println("POST Response Code :: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) { // success
                System.out.println("Test request sent successfully.");
            } else {
                System.out.println("POST request not worked");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Start an HTTP server to listen for incoming responses
    public static void startServer() {
        try {
            String hostPort = settings.getAsJsonObject("middlewareSettings")
                                      .getAsJsonObject("analyzerDetails")
                                      .get("hostPort").getAsString();
            int port = Integer.parseInt(hostPort);

            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new ResponseHandler());  // Changed to root context
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("Server started on port " + port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Handler to process incoming responses
    static class ResponseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                JsonParser parser = new JsonParser();
                JsonElement jsonElement = parser.parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
                JsonObject response = jsonElement.getAsJsonObject();
                System.out.println("Received response: " + response);

                String responseMessage = "Response received";
                exchange.sendResponseHeaders(200, responseMessage.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseMessage.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                exchange.sendResponseHeaders(405, -1); // 405 Method Not Allowed
            }
        }
    }
}
