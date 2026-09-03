# Intelligent Inventory Management System

A Java and PostgreSQL project for learning how a multi-user warehouse system keeps stock correct when several people work at the same time.

This repository is being built in tested milestones. The first milestone defines the domain, safety rules, and architecture before transaction code is added.

## Goals

- Record receiving, sales, transfers, and manual adjustments.
- Keep stock non-negative and prevent lost updates under concurrency.
- Generate useful low-stock and inventory-discrepancy alerts.
- Preserve transaction and audit histories.
- Apply role-based permissions for administrators, managers, and employees.
- Provide repeatable PostgreSQL integration and concurrency tests.

## Planned stack

- Java 17
- Maven
- JDBC
- PostgreSQL
- JUnit 5
- Docker Compose for a reproducible local database

## Design documents

- [Architecture](docs/architecture.md)
- [Database design](docs/database-design.md)

Setup, workflows, test evidence, diagrams, and lessons learned will be expanded as each working milestone lands.
