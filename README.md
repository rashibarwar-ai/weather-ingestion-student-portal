# weather-ingestion-student-portal
# 📌 Weather Data Ingestion System & Student Resource Portal

## 👩‍💻 Developed By
Rashi Barwar

---

# 🌦️ Question 1: High-Frequency Data Ingestion System

## 📖 Description
This system simulates 15 weather stations generating temperature data at random intervals.  
The data is processed using a Producer-Consumer model with thread safety.

## ⚙️ Key Features
- 15 Producer Threads (Weather Stations)
- Thread-safe buffer using synchronization / locks
- Consumer thread processes data
- Detects extreme temperature (>45°C)
- Stores alerts in SQLite database
- Uses Try-with-Resources to avoid memory leaks

## 🧠 Concepts Used
- Multithreading (Producer-Consumer Problem)
- Synchronization / Locks
- JDBC with SQLite
- Resource Management (Try-with-Resources)

---

# 🎓 Question 2: Modular Student Resource Portal

## 📖 Description
A pluggable system where faculty can upload study materials.  
Supports multiple file formats and encryption strategies.

## ⚙️ Key Features
- Supports PDF, Text, Markdown files
- Dynamic parser loading using Reflection
- File encryption using Strategy Pattern
- Transaction-safe database operations

---

## 🏗️ Design Patterns Used

### 🔹 Factory Pattern
Used to create appropriate file parser dynamically based on file type.

### 🔹 Strategy Pattern
Used to apply different encryption techniques (AES / No Encryption).

### 🔹 Dependency Injection (Manual DI)
Implemented using Java Reflection to dynamically load parser classes.

### 🔹 Singleton Pattern
Used for database connection.

**Justification:**
Singleton ensures only one database connection instance is used throughout the application, reducing memory usage and improving performance.

---

## 🗄️ Database Handling (JDBC)

- Used JDBC transactions:
```java
conn.setAutoCommit(false);
