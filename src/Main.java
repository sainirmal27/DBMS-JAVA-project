import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public class Main {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/campus_allocation_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "root"; // <-- Your MySQL password
    private static final String ADMIN_PIN = "2026";

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                Path htmlPath = Path.of("src/index.html");
                if (!Files.exists(htmlPath)) htmlPath = Path.of("index.html");
                byte[] content = Files.readAllBytes(htmlPath);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(content); }
            }
        });

        server.createContext("/api/departments", exchange -> {
            StringBuilder json = new StringBuilder("[");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT dept_id, dept_code, dept_name FROM departments ORDER BY dept_code")) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    json.append(String.format("{\"id\":%d,\"code\":\"%s\",\"name\":\"%s\"}",
                            rs.getInt("dept_id"), esc(rs.getString("dept_code")), esc(rs.getString("dept_name"))));
                    first = false;
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
                return;
            }
            json.append("]");
            sendJson(exchange, 200, json.toString());
        });

        server.createContext("/api/rooms/search", exchange -> {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            String date = q.getOrDefault("date", "");
            String start = q.getOrDefault("start", "");
            String end = q.getOrDefault("end", "");
            int minCap = Integer.parseInt(q.getOrDefault("capacity", "1"));
            String type = q.getOrDefault("type", "ALL");
            boolean needAc = "true".equals(q.get("ac"));
            boolean needProj = "true".equals(q.get("projector"));

            StringBuilder sql = new StringBuilder(
                    "SELECT c.*, d.dept_code, " +
                            "(SELECT COUNT(*) FROM event_bookings eb WHERE eb.room_id = c.room_id AND eb.booking_date = ? " +
                            " AND eb.status IN ('PENDING','APPROVED') AND (? < eb.end_time AND ? > eb.start_time)) AS event_clash, " +
                            "(SELECT subject_code FROM regular_timetable rt WHERE rt.room_id = c.room_id AND rt.day_of_week = DAYNAME(?) " +
                            " AND (? < rt.end_time AND ? > rt.start_time) LIMIT 1) AS class_subject " +
                            "FROM classrooms c LEFT JOIN departments d ON c.dept_id = d.dept_id WHERE c.capacity >= ? "
            );

            if (!"ALL".equals(type)) sql.append("AND c.room_type = ? ");
            if (needAc) sql.append("AND c.has_ac = TRUE ");
            if (needProj) sql.append("AND c.has_projector = TRUE ");
            sql.append("ORDER BY c.room_id ASC");

            StringBuilder json = new StringBuilder("[");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, date);
                ps.setString(idx++, start);
                ps.setString(idx++, end);
                ps.setString(idx++, date);
                ps.setString(idx++, start);
                ps.setString(idx++, end);
                ps.setInt(idx++, minCap);
                if (!"ALL".equals(type)) ps.setString(idx++, type);

                ResultSet rs = ps.executeQuery();
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    int eventClash = rs.getInt("event_clash");
                    String classSub = rs.getString("class_subject");
                    String status = "AVAILABLE";
                    String reason = "Open for slot request";
                    if (classSub != null) {
                        status = "CLASS_OCCUPIED";
                        reason = "Regular Class: " + classSub;
                    } else if (eventClash > 0) {
                        status = "BOOKED";
                        reason = "Reserved / Pending Approval";
                    }

                    json.append(String.format(
                            "{\"room_id\":%d,\"room_number\":\"%s\",\"block\":\"%s\",\"capacity\":%d," +
                                    "\"type\":\"%s\",\"ac\":%b,\"projector\":%b,\"dept\":\"%s\",\"status\":\"%s\",\"reason\":\"%s\"}",
                            rs.getInt("room_id"), esc(rs.getString("room_number")), esc(rs.getString("block_name")),
                            rs.getInt("capacity"), esc(rs.getString("room_type")), rs.getBoolean("has_ac"),
                            rs.getBoolean("has_projector"), esc(rs.getString("dept_code")), status, esc(reason)
                    ));
                    first = false;
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
                return;
            }
            json.append("]");
            sendJson(exchange, 200, json.toString());
        });

        // Multi-Day Booking + Resource Add-ons + ACID Transaction
        server.createContext("/api/bookings/create", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) return;
            Map<String, String> body = parseBody(exchange);
            Connection conn = null;
            try {
                conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                conn.setAutoCommit(false);

                int roomId = Integer.parseInt(body.get("room_id"));
                LocalDate startDate = LocalDate.parse(body.get("booking_date"));
                String endStr = body.getOrDefault("end_date", body.get("booking_date"));
                LocalDate endDate = (endStr == null || endStr.isEmpty()) ? startDate : LocalDate.parse(endStr);
                if (endDate.isBefore(startDate)) endDate = startDate;
                if (endDate.isAfter(startDate.plusDays(6))) {
                    throw new SQLException("Max 7 consecutive days per booking.");
                }

                try (PreparedStatement lockPs = conn.prepareStatement("SELECT room_id FROM classrooms WHERE room_id = ? FOR UPDATE")) {
                    lockPs.setInt(1, roomId);
                    lockPs.executeQuery();
                }

                String resList = body.getOrDefault("resources", "").trim();
                String insertSql = "INSERT INTO event_bookings (room_id, event_name, organizer_name, dept_id, booking_date, start_time, end_time, expected_attendees) VALUES (?,?,?,?,?,?,?,?)";
                String resSql = "INSERT INTO booking_resources (booking_id, resource_name) VALUES (?,?)";

                int daysBooked = 0;
                for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
                    int newBookingId = -1;
                    try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, roomId);
                        ps.setString(2, body.get("event_name"));
                        ps.setString(3, body.get("organizer_name"));
                        ps.setInt(4, Integer.parseInt(body.get("dept_id")));
                        ps.setString(5, d.toString());
                        ps.setString(6, body.get("start_time"));
                        ps.setString(7, body.get("end_time"));
                        ps.setInt(8, Integer.parseInt(body.get("attendees")));
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) newBookingId = keys.getInt(1);
                        }
                    }
                    if (newBookingId > 0 && !resList.isEmpty()) {
                        try (PreparedStatement rps = conn.prepareStatement(resSql)) {
                            for (String item : resList.split(",")) {
                                if (!item.trim().isEmpty()) {
                                    rps.setInt(1, newBookingId);
                                    rps.setString(2, item.trim());
                                    rps.addBatch();
                                }
                            }
                            rps.executeBatch();
                        }
                    }
                    daysBooked++;
                }

                conn.commit();
                sendJson(exchange, 200, "{\"message\":\"Booked for " + daysBooked + " day(s)! Status: PENDING.\"}");
            } catch (SQLException e) {
                if (conn != null) try { conn.rollback(); } catch (SQLException ignored) {}
                sendJson(exchange, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            } finally {
                if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) {}
            }
        });

        // List Bookings (Includes Resource Add-ons)
        server.createContext("/api/bookings/list", exchange -> {
            String sql = "SELECT eb.*, c.room_number, c.block_name, c.room_type, d.dept_code, " +
                    "(SELECT GROUP_CONCAT(resource_name SEPARATOR ', ') FROM booking_resources br WHERE br.booking_id = eb.booking_id) AS resources " +
                    "FROM event_bookings eb " +
                    "JOIN classrooms c ON eb.room_id = c.room_id " +
                    "JOIN departments d ON eb.dept_id = d.dept_id " +
                    "ORDER BY eb.booking_date DESC, eb.start_time ASC";
            StringBuilder json = new StringBuilder("[");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    json.append(String.format(
                            "{\"booking_id\":%d,\"room_number\":\"%s\",\"block\":\"%s\",\"room_type\":\"%s\"," +
                                    "\"event_name\":\"%s\",\"organizer\":\"%s\",\"dept\":\"%s\",\"date\":\"%s\"," +
                                    "\"start\":\"%s\",\"end\":\"%s\",\"attendees\":%d,\"status\":\"%s\",\"remarks\":\"%s\",\"resources\":\"%s\"}",
                            rs.getInt("booking_id"), esc(rs.getString("room_number")), esc(rs.getString("block_name")),
                            esc(rs.getString("room_type")), esc(rs.getString("event_name")), esc(rs.getString("organizer_name")),
                            esc(rs.getString("dept_code")), rs.getString("booking_date"), rs.getString("start_time"),
                            rs.getString("end_time"), rs.getInt("expected_attendees"), rs.getString("status"),
                            esc(rs.getString("admin_remarks")), esc(rs.getString("resources"))
                    ));
                    first = false;
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
                return;
            }
            json.append("]");
            sendJson(exchange, 200, json.toString());
        });

        // Admin Approve / Reject
        server.createContext("/api/bookings/approve", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) return;
            Map<String, String> body = parseBody(exchange);
            if (!ADMIN_PIN.equals(body.get("pin"))) {
                sendJson(exchange, 403, "{\"error\":\"Invalid PIN (2026).\"}");
                return;
            }
            String sql = "UPDATE event_bookings SET status = ?, admin_remarks = ? WHERE booking_id = ?";
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, body.get("status"));
                ps.setString(2, body.getOrDefault("remarks", "Updated"));
                ps.setInt(3, Integer.parseInt(body.get("booking_id")));
                ps.executeUpdate();
                sendJson(exchange, 200, "{\"message\":\"Updated to " + esc(body.get("status")) + "\"}");
            } catch (Exception e) {
                sendJson(exchange, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        // Emergency Room Swap (1-Click Relocate to Nearest Free Matching Room)
        server.createContext("/api/bookings/swap", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) return;
            Map<String, String> body = parseBody(exchange);
            if (!ADMIN_PIN.equals(body.get("pin"))) {
                sendJson(exchange, 403, "{\"error\":\"Invalid PIN (2026).\"}");
                return;
            }
            int bookingId = Integer.parseInt(body.get("booking_id"));
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                int oldRoomId, attendees;
                String bDate, sTime, eTime;
                try (PreparedStatement ps = conn.prepareStatement("SELECT room_id, booking_date, start_time, end_time, expected_attendees FROM event_bookings WHERE booking_id = ?")) {
                    ps.setInt(1, bookingId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) { sendJson(exchange, 404, "{\"error\":\"Booking not found\"}"); return; }
                    oldRoomId = rs.getInt("room_id");
                    bDate = rs.getString("booking_date");
                    sTime = rs.getString("start_time");
                    eTime = rs.getString("end_time");
                    attendees = rs.getInt("expected_attendees");
                }

                String findSql =
                        "SELECT c.room_id, c.room_number, c.block_name FROM classrooms c " +
                                "WHERE c.room_id != ? AND c.capacity >= ? " +
                                "AND NOT EXISTS (SELECT 1 FROM event_bookings eb WHERE eb.room_id = c.room_id AND eb.booking_date = ? AND eb.status IN ('PENDING','APPROVED') AND (? < eb.end_time AND ? > eb.start_time)) " +
                                "AND NOT EXISTS (SELECT 1 FROM regular_timetable rt WHERE rt.room_id = c.room_id AND rt.day_of_week = DAYNAME(?) AND (? < rt.end_time AND ? > rt.start_time)) " +
                                "ORDER BY c.capacity ASC LIMIT 1";

                int newRoomId = -1;
                String newRoomLabel = "";
                try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                    ps.setInt(1, oldRoomId);
                    ps.setInt(2, attendees);
                    ps.setString(3, bDate);
                    ps.setString(4, sTime);
                    ps.setString(5, eTime);
                    ps.setString(6, bDate);
                    ps.setString(7, sTime);
                    ps.setString(8, eTime);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        newRoomId = rs.getInt("room_id");
                        newRoomLabel = rs.getString("room_number") + " (" + rs.getString("block_name") + ")";
                    }
                }

                if (newRoomId == -1) {
                    sendJson(exchange, 400, "{\"error\":\"No alternative free room with enough capacity found.\"}");
                    return;
                }

                try (PreparedStatement ups = conn.prepareStatement("UPDATE event_bookings SET room_id = ?, admin_remarks = 'Emergency Relocated' WHERE booking_id = ?")) {
                    ups.setInt(1, newRoomId);
                    ups.setInt(2, bookingId);
                    ups.executeUpdate();
                }
                sendJson(exchange, 200, "{\"message\":\"Relocated to " + esc(newRoomLabel) + "!\"}");
            } catch (Exception e) {
                sendJson(exchange, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        // Waitlist: Join
        server.createContext("/api/waitlist/join", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) return;
            Map<String, String> body = parseBody(exchange);
            String sql = "INSERT INTO waitlist (room_id, event_name, organizer_name, dept_id, booking_date, start_time, end_time, expected_attendees) VALUES (?,?,?,?,?,?,?,?)";
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, Integer.parseInt(body.get("room_id")));
                ps.setString(2, body.get("event_name"));
                ps.setString(3, body.get("organizer_name"));
                ps.setInt(4, Integer.parseInt(body.get("dept_id")));
                ps.setString(5, body.get("booking_date"));
                ps.setString(6, body.get("start_time"));
                ps.setString(7, body.get("end_time"));
                ps.setInt(8, Integer.parseInt(body.get("attendees")));
                ps.executeUpdate();
                sendJson(exchange, 200, "{\"message\":\"Added to Waitlist! You have priority if this slot opens.\"}");
            } catch (Exception e) {
                sendJson(exchange, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        // Waitlist: List
        server.createContext("/api/waitlist/list", exchange -> {
            String sql = "SELECT w.*, c.room_number, c.block_name, d.dept_code FROM waitlist w " +
                    "JOIN classrooms c ON w.room_id = c.room_id " +
                    "JOIN departments d ON w.dept_id = d.dept_id ORDER BY w.created_at ASC";
            StringBuilder json = new StringBuilder("[");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    json.append(String.format(
                            "{\"id\":%d,\"room_number\":\"%s\",\"block\":\"%s\",\"event\":\"%s\",\"organizer\":\"%s\",\"dept\":\"%s\",\"date\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"attendees\":%d}",
                            rs.getInt("waitlist_id"), esc(rs.getString("room_number")), esc(rs.getString("block_name")),
                            esc(rs.getString("event_name")), esc(rs.getString("organizer_name")), esc(rs.getString("dept_code")),
                            rs.getString("booking_date"), rs.getString("start_time"), rs.getString("end_time"), rs.getInt("expected_attendees")
                    ));
                    first = false;
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
                return;
            }
            json.append("]");
            sendJson(exchange, 200, json.toString());
        });

        // Waitlist: Promote to Booking
        server.createContext("/api/waitlist/promote", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) return;
            Map<String, String> body = parseBody(exchange);
            if (!ADMIN_PIN.equals(body.get("pin"))) {
                sendJson(exchange, 403, "{\"error\":\"Invalid PIN (2026).\"}");
                return;
            }
            int wid = Integer.parseInt(body.get("waitlist_id"));
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM waitlist WHERE waitlist_id = ?")) {
                    ps.setInt(1, wid);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) { sendJson(exchange, 404, "{\"error\":\"Waitlist item not found\"}"); return; }
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO event_bookings (room_id, event_name, organizer_name, dept_id, booking_date, start_time, end_time, expected_attendees, status, admin_remarks) VALUES (?,?,?,?,?,?,?,?,'APPROVED','Promoted from Waitlist')")) {
                        ins.setInt(1, rs.getInt("room_id"));
                        ins.setString(2, rs.getString("event_name"));
                        ins.setString(3, rs.getString("organizer_name"));
                        ins.setInt(4, rs.getInt("dept_id"));
                        ins.setString(5, rs.getString("booking_date"));
                        ins.setString(6, rs.getString("start_time"));
                        ins.setString(7, rs.getString("end_time"));
                        ins.setInt(8, rs.getInt("expected_attendees"));
                        ins.executeUpdate();
                    }
                }
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM waitlist WHERE waitlist_id = ?")) {
                    del.setInt(1, wid);
                    del.executeUpdate();
                }
                sendJson(exchange, 200, "{\"message\":\"Promoted from Waitlist to APPROVED!\"}");
            } catch (SQLException e) {
                sendJson(exchange, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
            }
        });

        // Block Utilization Analytics
        server.createContext("/api/analytics", exchange -> {
            String sql = "SELECT c.block_name, COUNT(DISTINCT c.room_id) AS total_rooms, " +
                    "COUNT(eb.booking_id) AS total_bookings " +
                    "FROM classrooms c " +
                    "LEFT JOIN event_bookings eb ON c.room_id = eb.room_id AND eb.status IN ('PENDING','APPROVED') " +
                    "GROUP BY c.block_name ORDER BY c.block_name";
            StringBuilder json = new StringBuilder("[");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) json.append(",");
                    json.append(String.format("{\"block\":\"%s\",\"rooms\":%d,\"bookings\":%d}",
                            esc(rs.getString("block_name")), rs.getInt("total_rooms"), rs.getInt("total_bookings")));
                    first = false;
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
                return;
            }
            json.append("]");
            sendJson(exchange, 200, json.toString());
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Running -> http://localhost:8080");
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static Map<String, String> parseBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return parseQuery(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
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

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}