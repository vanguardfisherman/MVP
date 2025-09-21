# GymLog (Java + SQLite)

App de **consola** para registrar tus **pesos del gym**, repeticiones y RPE. Guarda todo en `gymlog.db` (SQLite), calcula **PRs** y **1RM estimada**, y permite **exportar a CSV**.

## ✨ Funcionalidades
- Registrar set (hoy): ejercicio, peso (kg), reps, RPE (opcional).
- Ver últimos sets por ejercicio.
- PR por 1/3/5/8/10 reps y **1RM estimada (Epley)**.
- Exportar a CSV (filtros opcionales por ejercicio y fechas).  
  > El CSV se crea en la **carpeta de trabajo actual** con nombre `gymlog_export_<timestamp>.csv`.

## 🧰 Requisitos
- Java 17+
- Maven 3.9+

## 🚀 Ejecución

**IntelliJ IDEA**
1. Cargar Maven: *View → Tool Windows → Maven → Reload All*.
2. Abrir `Main.java` y ejecutar **Run ▶️**.  
   _Si el IDE no detecta el driver, añade_:  
   `Class.forName("org.sqlite.JDBC");`

**Terminal**
```bash
mvn -q clean package
java -jar target/gymlog-1.0.0.jar
