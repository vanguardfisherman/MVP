package com.chisai.gymlog;

import java.sql.*;
import java.time.LocalDate;
import java.util.Scanner;

public class Main {
    private static final String DB_URL = "jdbc:sqlite:gymlog.db";

    public static void main(String[] args) {
        try {
            initDb();
            loopMenu();
        } catch (Exception e) {
            System.err.println("Error fatal: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static void exportarCSV(Scanner sc) throws Exception {
        System.out.print("Ejercicio (deja vacío para TODOS): ");
        String nombreEj = sc.nextLine().trim();

        System.out.print("Fecha desde (yyyy-MM-dd, vacío = sin límite): ");
        String desde = sc.nextLine().trim();
        System.out.print("Fecha hasta (yyyy-MM-dd, vacío = sin límite): ");
        String hasta = sc.nextLine().trim();

        Long ejercicioId = null;
        if (!nombreEj.isEmpty()) {
            ejercicioId = findEjercicio(nombreEj);
            if (ejercicioId == null) {
                System.out.println("No existe ese ejercicio.");
                return;
            }
        }

        StringBuilder sql = new StringBuilder("""
        SELECT s.fecha, ej.nombre AS ejercicio, st.peso_kg, st.reps, st.rpe
        FROM sets st
        JOIN sesiones s ON s.id = st.sesion_id
        JOIN ejercicios ej ON ej.id = st.ejercicio_id
        WHERE 1=1
    """);

        // filtros dinámicos
        if (ejercicioId != null) sql.append(" AND st.ejercicio_id = ? ");
        if (!desde.isEmpty())   sql.append(" AND s.fecha >= ? ");
        if (!hasta.isEmpty())   sql.append(" AND s.fecha <= ? ");
        sql.append(" ORDER BY s.fecha ASC, st.id ASC ");

        try (Connection c = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = c.prepareStatement(sql.toString())) {

            int idx = 1;
            if (ejercicioId != null) ps.setLong(idx++, ejercicioId);
            if (!desde.isEmpty())   ps.setString(idx++, desde);
            if (!hasta.isEmpty())   ps.setString(idx++, hasta);

            String fileName = "gymlog_export_" + System.currentTimeMillis() + ".csv";
            try (ResultSet rs = ps.executeQuery();
                 java.io.PrintWriter out = new java.io.PrintWriter(fileName, java.nio.charset.StandardCharsets.UTF_8)) {

                out.println("fecha,ejercicio,peso_kg,reps,rpe");
                while (rs.next()) {
                    String fecha = rs.getString("fecha");
                    String ej = rs.getString("ejercicio").replace("\"", "\"\"");
                    double peso = rs.getDouble("peso_kg");
                    int reps = rs.getInt("reps");
                    double rpe = rs.getDouble("rpe");
                    boolean rpeNull = rs.wasNull();
                    // CSV simple (escapa comillas)
                    out.printf("%s,\"%s\",%.2f,%d,%s%n",
                            fecha, ej, peso, reps, (rpeNull ? "" : String.valueOf(rpe)));
                }
            }
            System.out.println("✅ Exportado a CSV. Archivo generado en esta carpeta.");
        }
    }


    private static void loopMenu() throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.println("\n=== GYMLOG (Chisai) ===");
            System.out.println("1) Registrar set rápido (hoy)");
            System.out.println("2) Ver últimos sets de un ejercicio");
            System.out.println("3) Ver PR y 1RM estimada de un ejercicio");
            System.out.println("4) Exportar CSV (rango opcional)");
            System.out.println("5) Salir");
            System.out.print("Opción: ");
            String op = sc.nextLine().trim();

            switch (op) {
                case "1" -> registrarSetRapido(sc);
                case "2" -> verUltimosSets(sc);
                case "3" -> verPRy1RM(sc);
                case "4" -> exportarCSV(sc);
                case "5" -> { System.out.println("¡Nos vemos!"); return; }
                default -> System.out.println("Opción inválida.");
            }
        }
    }


    private static void verPRy1RM(Scanner sc) throws Exception {
        System.out.print("Ejercicio: ");
        String nombreEj = sc.nextLine().trim();
        Long ejercicioId = findEjercicio(nombreEj);
        if (ejercicioId == null) {
            System.out.println("No existe ese ejercicio todavía.");
            return;
        }

        try (Connection c = DriverManager.getConnection(DB_URL)) {
            // PR por 1 rep (mejor peso en cualquier número de reps usando 1RM estimada)
            String sql = """
            SELECT s.fecha, st.peso_kg, st.reps
            FROM sets st
            JOIN sesiones s ON s.id = st.sesion_id
            WHERE st.ejercicio_id = ?
        """;
            double best1RM = 0.0;
            String bestFecha = null;
            double bestPeso = 0.0;
            int bestReps = 0;

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, ejercicioId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String fecha = rs.getString("fecha");
                        double peso = rs.getDouble("peso_kg");
                        int reps = rs.getInt("reps");
                        double oneRM = epley1RM(peso, reps);
                        if (oneRM > best1RM) {
                            best1RM = oneRM;
                            bestFecha = fecha;
                            bestPeso = peso;
                            bestReps = reps;
                        }
                    }
                }
            }

            if (bestFecha == null) {
                System.out.println("Sin registros para ese ejercicio aún.");
                return;
            }

            System.out.printf("Mejor 1RM estimada (Epley) para %s: %.2f kg (lograda el %s con %.2f kg x %d reps)%n",
                    nombreEj, best1RM, bestFecha, bestPeso, bestReps);

            // PR estricto por rep-count comunes (1,3,5,8,10)
            int[] repTargets = {1, 3, 5, 8, 10};
            for (int target : repTargets) {
                double pr = mejorPesoParaReps(c, ejercicioId, target);
                if (pr > 0) {
                    System.out.printf("PR %2d reps: %.2f kg%n", target, pr);
                }
            }
        }
    }

    private static double epley1RM(double peso, int reps) {
        if (reps <= 1) return peso; // si fue 1 rep, el 1RM es el mismo peso
        return peso * (1.0 + reps / 30.0);
    }

    private static double mejorPesoParaReps(Connection c, long ejercicioId, int repsObjetivo) throws SQLException {
        String q = """
        SELECT MAX(peso_kg) AS max_peso
        FROM sets
        WHERE ejercicio_id = ? AND reps = ?
    """;
        try (PreparedStatement ps = c.prepareStatement(q)) {
            ps.setLong(1, ejercicioId);
            ps.setInt(2, repsObjetivo);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("max_peso");
            }
        }
        return 0.0;
    }


    private static void registrarSetRapido(Scanner sc) throws Exception {
        System.out.print("Ejercicio (ej. Press banca, Sentadilla): ");
        String nombreEj = sc.nextLine().trim();
        System.out.print("Peso (kg): ");
        double peso = Double.parseDouble(sc.nextLine().trim());
        System.out.print("Reps: ");
        int reps = Integer.parseInt(sc.nextLine().trim());
        System.out.print("RPE (opcional, enter para omitir): ");
        String rpeStr = sc.nextLine().trim();
        Double rpe = rpeStr.isEmpty() ? null : Double.parseDouble(rpeStr);

        long ejercicioId = ensureEjercicio(nombreEj);
        long sesionId = ensureSesion(LocalDate.now().toString()); // yyyy-MM-dd

        try (Connection c = DriverManager.getConnection(DB_URL)) {
            String sql = """
                INSERT INTO sets (sesion_id, ejercicio_id, peso_kg, reps, rpe)
                VALUES (?, ?, ?, ?, ?)
            """;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, sesionId);
                ps.setLong(2, ejercicioId);
                ps.setDouble(3, peso);
                ps.setInt(4, reps);
                if (rpe == null) ps.setNull(5, Types.REAL); else ps.setDouble(5, rpe);
                ps.executeUpdate();
            }
        }
        System.out.println("✅ Set guardado: " + nombreEj + " | " + peso + " kg x " + reps + (rpe == null ? "" : " | RPE " + rpe));
    }

    private static void verUltimosSets(Scanner sc) throws Exception {
        System.out.print("Ejercicio: ");
        String nombreEj = sc.nextLine().trim();
        Long ejercicioId = findEjercicio(nombreEj);
        if (ejercicioId == null) {
            System.out.println("No existe ese ejercicio todavía.");
            return;
        }
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            String sql = """
                SELECT s.fecha, st.peso_kg, st.reps, st.rpe
                FROM sets st
                JOIN sesiones s ON s.id = st.sesion_id
                WHERE st.ejercicio_id = ?
                ORDER BY s.fecha DESC, st.id DESC
                LIMIT 20
            """;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, ejercicioId);
                try (ResultSet rs = ps.executeQuery()) {
                    System.out.println("— Últimos sets de " + nombreEj + " —");
                    int count = 0;
                    while (rs.next()) {
                        String fecha = rs.getString("fecha");
                        double peso = rs.getDouble("peso_kg");
                        int reps = rs.getInt("reps");
                        double rpe = rs.getDouble("rpe");
                        boolean rpeWasNull = rs.wasNull();
                        System.out.printf("%s | %.2f kg x %d%s%n",
                                fecha, peso, reps, rpeWasNull ? "" : " | RPE " + rpe);
                        count++;
                    }
                    if (count == 0) System.out.println("(sin registros aún)");
                }
            }
        }
    }

    // ─────────────────────────── Infra DB ───────────────────────────
    private static void initDb() throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL);
             Statement st = c.createStatement()) {
            st.execute("""
                PRAGMA foreign_keys = ON;
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS ejercicios (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  nombre TEXT NOT NULL UNIQUE
                );
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS sesiones (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  fecha TEXT NOT NULL  -- ISO yyyy-MM-dd
                );
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS sets (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  sesion_id INTEGER NOT NULL,
                  ejercicio_id INTEGER NOT NULL,
                  peso_kg REAL NOT NULL,
                  reps INTEGER NOT NULL,
                  rpe REAL,
                  FOREIGN KEY (sesion_id) REFERENCES sesiones(id) ON DELETE CASCADE,
                  FOREIGN KEY (ejercicio_id) REFERENCES ejercicios(id) ON DELETE CASCADE
                );
            """);
        }
    }

    private static long ensureSesion(String fechaIso) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            // ¿Ya existe sesión para esa fecha?
            String q = "SELECT id FROM sesiones WHERE fecha = ?";
            try (PreparedStatement ps = c.prepareStatement(q)) {
                ps.setString(1, fechaIso);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong("id");
                }
            }
            // Crear
            String ins = "INSERT INTO sesiones (fecha) VALUES (?)";
            try (PreparedStatement ps = c.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, fechaIso);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private static long ensureEjercicio(String nombre) throws Exception {
        Long id = findEjercicio(nombre);
        if (id != null) return id;
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            String ins = "INSERT INTO ejercicios (nombre) VALUES (?)";
            try (PreparedStatement ps = c.prepareStatement(ins, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, nombre);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private static Long findEjercicio(String nombre) throws Exception {
        try (Connection c = DriverManager.getConnection(DB_URL)) {
            String q = "SELECT id FROM ejercicios WHERE nombre = ?";
            try (PreparedStatement ps = c.prepareStatement(q)) {
                ps.setString(1, nombre);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong("id");
                    return null;
                }
            }
        }
    }
}
