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

    private static void loopMenu() throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.println("\n=== GYMLOG (Chisai) ===");
            System.out.println("1) Registrar set rápido (hoy)");
            System.out.println("2) Ver últimos sets de un ejercicio");
            System.out.println("3) Salir");
            System.out.print("Opción: ");
            String op = sc.nextLine().trim();

            switch (op) {
                case "1" -> registrarSetRapido(sc);
                case "2" -> verUltimosSets(sc);
                case "3" -> { System.out.println("¡Nos vemos!"); return; }
                default -> System.out.println("Opción inválida.");
            }
        }
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
