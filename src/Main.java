import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class Main {

    // Update DB_PASS if your local MySQL root password is different
    private static final String DB_URL = "jdbc:mysql://localhost:3306/campus_allocation_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "root";
    private static final String ADMIN_PIN = "2026";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", Main::handleHome);
        server.createContext("/api/rooms", Main::handleGetRooms);
        server.createContext("/api/bookings", Main::handleGetBookings);
        server.createContext("/api/book", Main::handleBook);
        server.createContext("/api/admin/login", Main::handleAdminLogin);
        server.createContext("/api/admin/status", Main::handleUpdateStatus);
        server.createContext("/api/admin/swap", Main::handleEmergencySwap);
        server.createContext("/api/delete-booking", Main::handleDeleteBooking);
        server.createContext("/api/analytics", Main::handleAnalytics);
        server.createContext("/api/waitlist", Main::handleAddWaitlist);
        server.createContext("/api/waitlist/promote", Main::handlePromoteWaitlist);
        server.createContext("/api/waitlist/delete", Main::handleDeleteWaitlist);

        server.setExecutor(null);
        server.start();
        System.out.println("====================================================");
        System.out.println("Smart Campus Server running -> http://localhost:8080");
        System.out.println("Admin PIN: " + ADMIN_PIN);
        System.out.println("====================================================");
    }

    // Serve src/index.html
    private static void handleHome(HttpExchange ex) throws IOException {
        Path path = Files.exists(Paths.get("src/index.html"))
                ? Paths.get("src/index.html")
                : Paths.get("index.html");
        byte[] bytes = Files.readAllBytes(path);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // GET /api/rooms?date=YYYY-MM-DD&start=HH:MM&end=HH:MM
    private static void handleGetRooms(HttpExchange ex) throws IOException {
        Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
        String dateStr = q.getOrDefault("date", LocalDate.now().toString());
        String startTime = toSqlTime(q.getOrDefault("start", "09:00"));
        String endTime = toSqlTime(q.getOrDefault("end", "11:00"));

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            String dayOfWeek = LocalDate.parse(dateStr).getDayOfWeek().name();

            // 1. Fetch regular timetable clashes
            Map<Integer, String> classMap = new HashMap<>();
            String ttSql = "SELECT room_id, course_code FROM regular_timetable " +
                    "WHERE day_of_week = ? AND start_time < ? AND end_time > ?";
            try (PreparedStatement ps = conn.prepareStatement(ttSql)) {
                ps.setString(1, dayOfWeek);
                ps.setString(2, endTime);
                ps.setString(3, startTime);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    classMap.put(rs.getInt("room_id"), rs.getString("course_code"));
                }
            }

            // 2. Fetch active bookings clashes
            Map<Integer, String> bookingMap = new HashMap<>();
            String bkSql = "SELECT room_id, event_name, status FROM event_bookings " +
                    "WHERE event_date = ? AND status != 'REJECTED' AND start_time < ? AND end_time > ?";
            try (PreparedStatement ps = conn.prepareStatement(bkSql)) {
                ps.setString(1, dateStr);
                ps.setString(2, endTime);
                ps.setString(3, startTime);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    bookingMap.put(rs.getInt("room_id"),
                            rs.getString("event_name") + " (" + rs.getString("status") + ")");
                }
            }

            // 3. Build Rooms JSON
            StringBuilder roomsJson = new StringBuilder("[");
            boolean first = true;
            String roomSql = "SELECT * FROM classrooms ORDER BY block_name, room_id";
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(roomSql)) {
                while (rs.next()) {
                    if (!first) roomsJson.append(",");
                    first = false;
                    int id = rs.getInt("room_id");
                    String status = "FREE";
                    String detail = "Open for slot request";
                    if (classMap.containsKey(id)) {
                        status = "CLASS OCCUPIED";
                        detail = "Regular Class: " + classMap.get(id);
                    } else if (bookingMap.containsKey(id)) {
                        status = "BOOKED";
                        detail = "Reserved: " + bookingMap.get(id);
                    }
                    roomsJson.append("{")
                            .append("\"room_id\":").append(id).append(",")
                            .append("\"room_no\":\"").append(esc(rs.getString("room_no"))).append("\",")
                            .append("\"block_name\":\"").append(esc(rs.getString("block_name"))).append("\",")
                            .append("\"room_type\":\"").append(esc(rs.getString("room_type"))).append("\",")
                            .append("\"capacity\":").append(rs.getInt("capacity")).append(",")
                            .append("\"has_ac\":").append(rs.getBoolean("has_ac")).append(",")
                            .append("\"has_projector\":").append(rs.getBoolean("has_projector")).append(",")
                            .append("\"status\":\"").append(status).append("\",")
                            .append("\"detail\":\"").append(esc(detail)).append("\"")
                            .append("}");
                }
            }
            roomsJson.append("]");

            // 4. Build Departments JSON
            StringBuilder deptsJson = new StringBuilder("[");
            boolean firstDept = true;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT dept_id, dept_name FROM departments ORDER BY dept_id")) {
                while (rs.next()) {
                    if (!firstDept) deptsJson.append(",");
                    firstDept = false;
                    deptsJson.append("{\"dept_id\":").append(rs.getInt("dept_id"))
                            .append(",\"dept_name\":\"").append(esc(rs.getString("dept_name"))).append("\"}");
                }
            }
            deptsJson.append("]");

            sendJson(ex, 200, "{\"rooms\":" + roomsJson + ",\"departments\":" + deptsJson + "}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // GET /api/bookings
    private static void handleGetBookings(HttpExchange ex) throws IOException {
        String sql = "SELECT b.booking_id, b.room_id, c.room_no, c.block_name, d.dept_name, " +
                "b.event_name, b.organizer_name, b.expected_crowd, b.event_date, " +
                "DATE_FORMAT(b.start_time, '%H:%i') AS st, DATE_FORMAT(b.end_time, '%H:%i') AS et, b.status, " +
                "(SELECT GROUP_CONCAT(br.resource_name SEPARATOR ', ') FROM booking_resources br WHERE br.booking_id = b.booking_id) AS addons " +
                "FROM event_bookings b " +
                "JOIN classrooms c ON b.room_id = c.room_id " +
                "JOIN departments d ON b.dept_id = d.dept_id " +
                "ORDER BY b.event_date DESC, b.booking_id DESC";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                first = false;
                String addons = rs.getString("addons");
                json.append("{")
                        .append("\"booking_id\":").append(rs.getInt("booking_id")).append(",")
                        .append("\"room_id\":").append(rs.getInt("room_id")).append(",")
                        .append("\"room_no\":\"").append(esc(rs.getString("room_no"))).append("\",")
                        .append("\"block_name\":\"").append(esc(rs.getString("block_name"))).append("\",")
                        .append("\"dept_name\":\"").append(esc(rs.getString("dept_name"))).append("\",")
                        .append("\"event_name\":\"").append(esc(rs.getString("event_name"))).append("\",")
                        .append("\"organizer_name\":\"").append(esc(rs.getString("organizer_name"))).append("\",")
                        .append("\"expected_crowd\":").append(rs.getInt("expected_crowd")).append(",")
                        .append("\"event_date\":\"").append(esc(rs.getString("event_date"))).append("\",")
                        .append("\"start_time\":\"").append(esc(rs.getString("st"))).append("\",")
                        .append("\"end_time\":\"").append(esc(rs.getString("et"))).append("\",")
                        .append("\"status\":\"").append(esc(rs.getString("status"))).append("\",")
                        .append("\"addons\":\"").append(esc(addons == null ? "None" : addons)).append("\"")
                        .append("}");
            }
            json.append("]");
            sendJson(ex, 200, json.toString());
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // POST /api/book (Atomic JDBC Transaction supporting Single-Day & Multi-Day)
    private static void handleBook(HttpExchange ex) throws IOException {
        Map<String, String> p = parseForm(readBody(ex));
        try {
            int roomId = Integer.parseInt(p.get("room_id"));
            int deptId = Integer.parseInt(p.get("dept_id"));
            String eventName = p.getOrDefault("event_name", "").trim();
            String organizer = p.getOrDefault("organizer_name", "").trim();
            int crowd = Integer.parseInt(p.getOrDefault("expected_crowd", "30"));
            LocalDate startDate = LocalDate.parse(p.get("start_date"));
            String endDateParam = p.getOrDefault("end_date", "").trim();
            LocalDate endDate = endDateParam.isEmpty() ? startDate : LocalDate.parse(endDateParam);
            String startTime = toSqlTime(p.get("start_time"));
            String endTime = toSqlTime(p.get("end_time"));
            String addonsStr = p.getOrDefault("addons", "").trim();

            if (endDate.isBefore(startDate)) {
                sendJson(ex, 400, "{\"error\":\"End date cannot be before start date.\"}");
                return;
            }
            if (startDate.plusDays(14).isBefore(endDate)) {
                sendJson(ex, 400, "{\"error\":\"Multi-day booking is limited to 14 consecutive days.\"}");
                return;
            }

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                // Verify room capacity first
                try (PreparedStatement psCap = conn.prepareStatement("SELECT capacity, room_no FROM classrooms WHERE room_id = ?")) {
                    psCap.setInt(1, roomId);
                    ResultSet rsCap = psCap.executeQuery();
                    if (!rsCap.next()) {
                        sendJson(ex, 404, "{\"error\":\"Selected room does not exist.\"}");
                        return;
                    }
                    int cap = rsCap.getInt("capacity");
                    if (crowd > cap) {
                        sendJson(ex, 400, "{\"error\":\"Expected crowd (" + crowd + ") exceeds " + rsCap.getString("room_no") + " capacity (" + cap + ").\"}");
                        return;
                    }
                }

                conn.setAutoCommit(false);
                int daysBooked = 0;
                try {
                    String insSql = "INSERT INTO event_bookings (room_id, dept_id, event_name, organizer_name, expected_crowd, event_date, start_time, end_time, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')";
                    String resSql = "INSERT INTO booking_resources (booking_id, resource_name) VALUES (?, ?)";

                    for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
                        int bookingId;
                        try (PreparedStatement ps = conn.prepareStatement(insSql, Statement.RETURN_GENERATED_KEYS)) {
                            ps.setInt(1, roomId);
                            ps.setInt(2, deptId);
                            ps.setString(3, eventName);
                            ps.setString(4, organizer);
                            ps.setInt(5, crowd);
                            ps.setDate(6, java.sql.Date.valueOf(d));
                            ps.setString(7, startTime);
                            ps.setString(8, endTime);
                            ps.executeUpdate();
                            ResultSet keys = ps.getGeneratedKeys();
                            keys.next();
                            bookingId = keys.getInt(1);
                        }

                        if (!addonsStr.isEmpty()) {
                            try (PreparedStatement psRes = conn.prepareStatement(resSql)) {
                                for (String addon : addonsStr.split(",")) {
                                    String clean = addon.trim();
                                    if (!clean.isEmpty()) {
                                        psRes.setInt(1, bookingId);
                                        psRes.setString(2, clean);
                                        psRes.addBatch();
                                    }
                                }
                                psRes.executeBatch();
                            }
                        }
                        daysBooked++;
                    }
                    conn.commit();
                    sendJson(ex, 200, "{\"message\":\"Booking request submitted for " + daysBooked + (daysBooked == 1 ? " day" : " days") + " (Pending Admin Approval).\"}");
                } catch (SQLException sqlEx) {
                    conn.rollback();
                    sendJson(ex, 409, "{\"error\":\"" + esc(sqlEx.getMessage()) + "\"}");
                }
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // POST /api/admin/login
    private static void handleAdminLogin(HttpExchange ex) throws IOException {
        Map<String, String> p = parseForm(readBody(ex));
        if (ADMIN_PIN.equals(p.getOrDefault("pin", "").trim())) {
            sendJson(ex, 200, "{\"ok\":true}");
        } else {
            sendJson(ex, 401, "{\"error\":\"Incorrect Admin PIN\"}");
        }
    }

    // POST /api/admin/status (Approve / Reject)
    private static void handleUpdateStatus(HttpExchange ex) throws IOException {
        Map<String, String> p = parseForm(readBody(ex));
        try {
            int bookingId = Integer.parseInt(p.get("booking_id"));
            String status = p.get("status");
            if (!status.equals("APPROVED") && !status.equals("REJECTED")) {
                sendJson(ex, 400, "{\"error\":\"Invalid status\"}");
                return;
            }
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 PreparedStatement ps = conn.prepareStatement("UPDATE event_bookings SET status = ? WHERE booking_id = ?")) {
                ps.setString(1, status);
                ps.setInt(2, bookingId);
                ps.executeUpdate();
                sendJson(ex, 200, "{\"message\":\"Booking #" + bookingId + " marked as " + status + ".\"}");
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // POST /api/admin/swap (Emergency Room Swap using SQL Anti-Join)
    private static void handleEmergencySwap(HttpExchange ex) throws IOException {
        Map<String, String> p = parseForm(readBody(ex));
        try {
            int bookingId = Integer.parseInt(p.get("booking_id"));
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                conn.setAutoCommit(false);
                try {
                    int currentRoomId, crowd;
                    String eventDate, startTime, endTime;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT room_id, expected_crowd, event_date, start_time, end_time FROM event_bookings WHERE booking_id = ?")) {
                        ps.setInt(1, bookingId);
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            conn.rollback();
                            sendJson(ex, 404, "{\"error\":\"Booking not found\"}");
                            return;
                        }
                        currentRoomId = rs.getInt("room_id");
                        crowd = rs.getInt("expected_crowd");
                        eventDate = rs.getString("event_date");
                        startTime = rs.getString("start_time");
                        endTime = rs.getString("end_time");
                    }

                    String dayOfWeek = LocalDate.parse(eventDate).getDayOfWeek().name();
                    String findSql = "SELECT room_id, room_no, block_name FROM classrooms " +
                            "WHERE room_id != ? AND capacity >= ? " +
                            "AND room_id NOT IN (SELECT room_id FROM event_bookings WHERE event_date = ? AND status != 'REJECTED' AND start_time < ? AND end_time > ?) " +
                            "AND room_id NOT IN (SELECT room_id FROM regular_timetable WHERE day_of_week = ? AND start_time < ? AND end_time > ?) " +
                            "ORDER BY capacity ASC LIMIT 1";

                    int newRoomId;
                    String newRoomLabel;
                    try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                        ps.setInt(1, currentRoomId);
                        ps.setInt(2, crowd);
                        ps.setString(3, eventDate);
                        ps.setString(4, endTime);
                        ps.setString(5, startTime);
                        ps.setString(6, dayOfWeek);
                        ps.setString(7, endTime);
                        ps.setString(8, startTime);
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            conn.rollback();
                            sendJson(ex, 409, "{\"error\":\"No alternative free room with capacity >= " + crowd + " is available for this slot.\"}");
                            return;
                        }
                        newRoomId = rs.getInt("room_id");
                        newRoomLabel = rs.getString("room_no") + " (" + rs.getString("block_name") + ")";
                    }

                    try (PreparedStatement ps = conn.prepareStatement("UPDATE event_bookings SET room_id = ? WHERE booking_id = ?")) {
                        ps.setInt(1, newRoomId);
                        ps.setInt(2, bookingId);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    sendJson(ex, 200, "{\"message\":\"Emergency Swap complete! Moved Booking #" + bookingId + " to " + newRoomLabel + ".\"}");
                } catch (Exception inner) {
                    conn.rollback();
                    throw inner;
                }
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // DELETE or POST /api/delete-booking?id=X
    private static void handleDeleteBooking(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
            String idStr = q.get("id");
            if (idStr == null || idStr.isEmpty()) {
                Map<String, String> body = parseForm(readBody(ex));
                idStr = body.get("id");
            }
            int bookingId = Integer.parseInt(idStr);

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                try (PreparedStatement psRes = conn.prepareStatement("DELETE FROM booking_resources WHERE booking_id = ?")) {
                    psRes.setInt(1, bookingId);
                    psRes.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM event_bookings WHERE booking_id = ?")) {
                    ps.setInt(1, bookingId);
                    int rows = ps.executeUpdate();
                    if (rows > 0) {
                        sendJson(ex, 200, "{\"message\":\"Booking #" + bookingId + " permanently deleted.\"}");
                    } else {
                        sendJson(ex, 404, "{\"error\":\"Booking #" + bookingId + " not found.\"}");
                    }
                }
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // GET /api/analytics (Block Utilization + Waitlist Queue)
    private static void handleAnalytics(HttpExchange ex) throws IOException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            String blockSql = "SELECT c.block_name, COUNT(DISTINCT c.room_id) AS total_rooms, " +
                    "COUNT(DISTINCT CASE WHEN b.status IN ('APPROVED','PENDING') THEN b.booking_id END) AS active_bookings " +
                    "FROM classrooms c " +
                    "LEFT JOIN event_bookings b ON c.room_id = b.room_id " +
                    "GROUP BY c.block_name ORDER BY c.block_name";

            StringBuilder blocksJson = new StringBuilder("[");
            boolean firstB = true;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(blockSql)) {
                while (rs.next()) {
                    if (!firstB) blocksJson.append(",");
                    firstB = false;
                    blocksJson.append("{")
                            .append("\"block_name\":\"").append(esc(rs.getString("block_name"))).append("\",")
                            .append("\"total_rooms\":").append(rs.getInt("total_rooms")).append(",")
                            .append("\"active_bookings\":").append(rs.getInt("active_bookings"))
                            .append("}");
                }
            }
            blocksJson.append("]");

            String waitSql = "SELECT w.waitlist_id, w.room_id, c.room_no, c.block_name, w.event_name, " +
                    "w.organizer_name, w.dept_name, w.event_date, " +
                    "DATE_FORMAT(w.start_time, '%H:%i') AS st, DATE_FORMAT(w.end_time, '%H:%i') AS et, w.status " +
                    "FROM waitlist_queue w " +
                    "JOIN classrooms c ON w.room_id = c.room_id " +
                    "WHERE w.status = 'WAITING' " +
                    "ORDER BY w.event_date ASC, w.waitlist_id ASC";

            StringBuilder waitJson = new StringBuilder("[");
            boolean firstW = true;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(waitSql)) {
                while (rs.next()) {
                    if (!firstW) waitJson.append(",");
                    firstW = false;
                    waitJson.append("{")
                            .append("\"waitlist_id\":").append(rs.getInt("waitlist_id")).append(",")
                            .append("\"room_id\":").append(rs.getInt("room_id")).append(",")
                            .append("\"room_no\":\"").append(esc(rs.getString("room_no"))).append("\",")
                            .append("\"block_name\":\"").append(esc(rs.getString("block_name"))).append("\",")
                            .append("\"event_name\":\"").append(esc(rs.getString("event_name"))).append("\",")
                            .append("\"organizer_name\":\"").append(esc(rs.getString("organizer_name"))).append("\",")
                            .append("\"dept_name\":\"").append(esc(rs.getString("dept_name"))).append("\",")
                            .append("\"event_date\":\"").append(esc(rs.getString("event_date"))).append("\",")
                            .append("\"start_time\":\"").append(esc(rs.getString("st"))).append("\",")
                            .append("\"end_time\":\"").append(esc(rs.getString("et"))).append("\"")
                            .append("}");
                }
            }
            waitJson.append("]");

            sendJson(ex, 200, "{\"blocks\":" + blocksJson + ",\"waitlist\":" + waitJson + "}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // POST /api/waitlist
    private static void handleAddWaitlist(HttpExchange ex) throws IOException {
        Map<String, String> p = parseForm(readBody(ex));
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO waitlist_queue (room_id, event_name, organizer_name, dept_name, event_date, start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, Integer.parseInt(p.get("room_id")));
            ps.setString(2, p.getOrDefault("event_name", "Waitlist Request").trim());
            ps.setString(3, p.getOrDefault("organizer_name", "Coordinator").trim());
            ps.setString(4, p.getOrDefault("dept_name", "CSE").trim());
            ps.setString(5, p.get("event_date"));
            ps.setString(6, toSqlTime(p.get("start_time")));
            ps.setString(7, toSqlTime(p.get("end_time")));
            ps.executeUpdate();
            sendJson(ex, 200, "{\"message\":\"Added to Hall Waitlist Queue!\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // POST /api/waitlist/promote (Promote waitlisted class to APPROVED booking if slot is now free)
    private static void handlePromoteWaitlist(HttpExchange ex) throws IOException {
        Map<String, String> p = parseForm(readBody(ex));
        try {
            int waitlistId = Integer.parseInt(p.get("waitlist_id"));
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                conn.setAutoCommit(false);
                try {
                    int roomId;
                    String eventName, organizer, deptName, eventDate, startTime, endTime;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM waitlist_queue WHERE waitlist_id = ?")) {
                        ps.setInt(1, waitlistId);
                        ResultSet rs = ps.executeQuery();
                        if (!rs.next()) {
                            conn.rollback();
                            sendJson(ex, 404, "{\"error\":\"Waitlist entry not found.\"}");
                            return;
                        }
                        roomId = rs.getInt("room_id");
                        eventName = rs.getString("event_name");
                        organizer = rs.getString("organizer_name");
                        deptName = rs.getString("dept_name");
                        eventDate = rs.getString("event_date");
                        startTime = rs.getString("start_time");
                        endTime = rs.getString("end_time");
                    }

                    int deptId = 1;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT dept_id FROM departments WHERE dept_name = ? LIMIT 1")) {
                        ps.setString(1, deptName);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) deptId = rs.getInt("dept_id");
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO event_bookings (room_id, dept_id, event_name, organizer_name, expected_crowd, event_date, start_time, end_time, status) " +
                                    "VALUES (?, ?, ?, ?, 40, ?, ?, ?, 'APPROVED')")) {
                        ps.setInt(1, roomId);
                        ps.setInt(2, deptId);
                        ps.setString(3, eventName);
                        ps.setString(4, organizer);
                        ps.setString(5, eventDate);
                        ps.setString(6, startTime);
                        ps.setString(7, endTime);
                        ps.executeUpdate();
                    }

                    try (PreparedStatement ps = conn.prepareStatement("UPDATE waitlist_queue SET status = 'PROMOTED' WHERE waitlist_id = ?")) {
                        ps.setInt(1, waitlistId);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    sendJson(ex, 200, "{\"message\":\"Promoted '" + eventName + "' to an APPROVED booking!\"}");
                } catch (SQLException sqlEx) {
                    conn.rollback();
                    sendJson(ex, 409, "{\"error\":\"Cannot promote yet: " + esc(sqlEx.getMessage()) + " (Delete or Swap the existing booking first).\"}");
                }
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // DELETE or POST /api/waitlist/delete?id=X
    private static void handleDeleteWaitlist(HttpExchange ex) throws IOException {
        try {
            Map<String, String> q = parseForm(ex.getRequestURI().getRawQuery());
            int waitlistId = Integer.parseInt(q.get("id"));
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM waitlist_queue WHERE waitlist_id = ?")) {
                ps.setInt(1, waitlistId);
                ps.executeUpdate();
                sendJson(ex, 200, "{\"message\":\"Removed from Waitlist Queue.\"}");
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    // Helpers
    private static String toSqlTime(String t) {
        if (t == null || t.isEmpty()) return "09:00:00";
        return t.length() == 5 ? t + ":00" : t;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "");
    }
}