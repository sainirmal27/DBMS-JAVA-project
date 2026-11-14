import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class Main {
    static final String DB_URL = "jdbc:mysql://localhost:3306/campus_db";
    static final String USER = "root";
    static final String PASS = "root";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", Main::handleHome);
        server.createContext("/book", Main::handleBook);
        server.createContext("/delete", Main::handleDelete);
        server.start();
        System.out.println("Server running at -> http://localhost:8080");
    }

    private static void handleHome(HttpExchange exchange) throws IOException {
        Path path = Files.exists(Paths.get("src/index.html")) ? Paths.get("src/index.html") : Paths.get("index.html");
        String html = Files.readString(path);

        Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
        String msg = queryParams.getOrDefault("msg", "");
        String status = queryParams.getOrDefault("status", "ok");

        StringBuilder deptOptions = new StringBuilder();
        StringBuilder bookingRows = new StringBuilder();
        StringBuilder roomsJson = new StringBuilder("[");
        StringBuilder bookingsJson = new StringBuilder("[");

        try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
            // Fetch Departments
            ResultSet rsDepts = conn.createStatement().executeQuery("SELECT * FROM departments");
            while (rsDepts.next()) {
                deptOptions.append("<option value='").append(rsDepts.getInt("dept_id")).append("'>")
                        .append(rsDepts.getString("dept_name")).append(" (").append(rsDepts.getString("coordinator")).append(")</option>");
            }

            // Fetch Classrooms for JSON Live Filter
            ResultSet rsRooms = conn.createStatement().executeQuery("SELECT * FROM classrooms ORDER BY capacity ASC");
            boolean firstRoom = true;
            while (rsRooms.next()) {
                if (!firstRoom) roomsJson.append(",");
                firstRoom = false;
                roomsJson.append(String.format("{\"id\":%d,\"no\":\"%s\",\"block\":\"%s\",\"cap\":%d,\"proj\":\"%s\"}",
                        rsRooms.getInt("room_id"),
                        rsRooms.getString("room_no"),
                        rsRooms.getString("block_name"),
                        rsRooms.getInt("capacity"),
                        rsRooms.getString("projector")));
            }
            roomsJson.append("]");

            // Fetch Event Bookings using your exact 3-table INNER JOIN
            String joinSql = "SELECT e.event_id, e.event_name, e.event_theme, e.event_date, e.time_slot, e.expected_crowd, " +
                    "e.room_id, c.room_no, c.block_name, d.dept_name, d.coordinator " +
                    "FROM event_bookings e " +
                    "JOIN classrooms c ON e.room_id = c.room_id " +
                    "JOIN departments d ON e.dept_id = d.dept_id " +
                    "ORDER BY e.event_date ASC, e.event_id DESC";
            ResultSet rsJoin = conn.createStatement().executeQuery(joinSql);
            boolean firstBook = true;
            while (rsJoin.next()) {
                if (!firstBook) bookingsJson.append(",");
                firstBook = false;
                bookingsJson.append(String.format("{\"roomId\":%d,\"date\":\"%s\",\"slot\":\"%s\"}",
                        rsJoin.getInt("room_id"),
                        rsJoin.getString("event_date"),
                        rsJoin.getString("time_slot")));

                bookingRows.append("<tr>")
                        .append("<td>#").append(rsJoin.getInt("event_id")).append("</td>")
                        .append("<td><b>").append(rsJoin.getString("event_name")).append("</b><br><small>")
                        .append(rsJoin.getString("event_theme")).append("</small></td>")
                        .append("<td>").append(rsJoin.getString("event_date")).append("<br><small>")
                        .append(rsJoin.getString("time_slot")).append("</small></td>")
                        .append("<td>").append(rsJoin.getInt("expected_crowd")).append("</td>")
                        .append("<td><b>").append(rsJoin.getString("room_no")).append("</b> (")
                        .append(rsJoin.getString("block_name")).append(")</td>")
                        .append("<td>").append(rsJoin.getString("dept_name")).append("<br><small>")
                        .append(rsJoin.getString("coordinator")).append("</small></td>")
                        .append("<td><form action='/delete' method='POST' style='margin:0;'>")
                        .append("<input type='hidden' name='event_id' value='").append(rsJoin.getInt("event_id")).append("'>")
                        .append("<button type='submit' class='btn-del'>Release</button></form></td>")
                        .append("</tr>");
            }
            bookingsJson.append("]");
            if (bookingRows.length() == 0) {
                bookingRows.append("<tr><td colspan='7' style='text-align:center;'>No events scheduled yet.</td></tr>");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        String alertBox = "";
        if (!msg.isEmpty()) {
            String style = "err".equals(status) ? "background:#fee2e2;color:#991b1b;" : "background:#dcfce7;color:#166534;";
            alertBox = "<div class='alert' style='" + style + "'>" + msg + "</div>";
        }

        String response = html
                .replace("<!-- ALERT_BOX -->", alertBox)
                .replace("<!-- DEPT_OPTIONS -->", deptOptions.toString())
                .replace("<!-- BOOKING_ROWS -->", bookingRows.toString())
                .replace("<!-- ROOMS_JSON -->", roomsJson.toString())
                .replace("<!-- BOOKINGS_JSON -->", bookingsJson.toString());

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void handleBook(HttpExchange exchange) throws IOException {
        String redirectMsg = "";
        String status = "ok";

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQuery(body);

            String eventName = params.get("event_name");
            String eventTheme = params.get("event_theme");
            String eventDate = params.get("event_date");
            String timeSlot = params.get("time_slot");
            int crowd = Integer.parseInt(params.getOrDefault("expected_crowd", "50"));
            int deptId = Integer.parseInt(params.getOrDefault("dept_id", "1"));
            int chosenRoomId = Integer.parseInt(params.getOrDefault("room_id", "0"));

            try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
                int finalRoomId = chosenRoomId;
                String allocatedRoomName = "";

                if (chosenRoomId <= 0) {
                    // Smart Subquery to find best free room matching capacity
                    String findSql = "SELECT room_id, room_no, block_name FROM classrooms " +
                            "WHERE capacity >= ? AND room_id NOT IN " +
                            "(SELECT room_id FROM event_bookings WHERE event_date = ? AND time_slot = ?) " +
                            "ORDER BY capacity ASC LIMIT 1";
                    PreparedStatement psFind = conn.prepareStatement(findSql);
                    psFind.setInt(1, crowd);
                    psFind.setString(2, eventDate);
                    psFind.setString(3, timeSlot);
                    ResultSet rs = psFind.executeQuery();
                    if (rs.next()) {
                        finalRoomId = rs.getInt("room_id");
                        allocatedRoomName = rs.getString("room_no") + " (" + rs.getString("block_name") + ")";
                    } else {
                        status = "err";
                        redirectMsg = "No free classroom available on " + eventDate + " [" + timeSlot + "] for " + crowd + " students.";
                    }
                } else {
                    // Check manual room selection against conflicts and capacity
                    String checkSql = "SELECT room_no, block_name FROM classrooms WHERE room_id = ? AND capacity >= ? " +
                            "AND room_id NOT IN (SELECT room_id FROM event_bookings WHERE event_date = ? AND time_slot = ?)";
                    PreparedStatement psCheck = conn.prepareStatement(checkSql);
                    psCheck.setInt(1, chosenRoomId);
                    psCheck.setInt(2, crowd);
                    psCheck.setString(3, eventDate);
                    psCheck.setString(4, timeSlot);
                    ResultSet rs = psCheck.executeQuery();
                    if (rs.next()) {
                        allocatedRoomName = rs.getString("room_no") + " (" + rs.getString("block_name") + ")";
                    } else {
                        status = "err";
                        redirectMsg = "Selected room is already booked or too small for " + crowd + " students.";
                    }
                }

                if ("ok".equals(status) && finalRoomId > 0) {
                    String insSql = "INSERT INTO event_bookings (event_name, event_theme, event_date, time_slot, expected_crowd, room_id, dept_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    PreparedStatement psIns = conn.prepareStatement(insSql);
                    psIns.setString(1, eventName);
                    psIns.setString(2, eventTheme);
                    psIns.setString(3, eventDate);
                    psIns.setString(4, timeSlot);
                    psIns.setInt(5, crowd);
                    psIns.setInt(6, finalRoomId);
                    psIns.setInt(7, deptId);
                    psIns.executeUpdate();
                    redirectMsg = "Successfully allocated room " + allocatedRoomName + " for '" + eventName + "'.";
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        String loc = "/?status=" + status + "&msg=" + URLEncoder.encode(redirectMsg, StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Location", loc);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void handleDelete(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseQuery(body);
            int eventId = Integer.parseInt(params.getOrDefault("event_id", "0"));
            try (Connection conn = DriverManager.getConnection(DB_URL, USER, PASS)) {
                PreparedStatement ps = conn.prepareStatement("DELETE FROM event_bookings WHERE event_id = ?");
                ps.setInt(1, eventId);
                ps.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        exchange.getResponseHeaders().set("Location", "/?status=ok&msg=" + URLEncoder.encode("Room booking released successfully!", StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(303, -1);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}