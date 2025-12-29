# HomeWatts — PV Analytics Platform

A portfolio project that demonstrates end-to-end engineering: data ingestion, secure APIs, a modern SPA, and production-grade DevOps. HomeWatts analyzes photovoltaic (PV) system data, normalizes it into a PostgreSQL model, and serves real-time + historical insights in a React dashboard. The project is built to showcase architecture decisions, security hardening, and automated delivery.

![Build](https://github.com/MarkusDunkel/homewatts/actions/workflows/deploy-staging.yml/badge.svg)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=MarkusDunkel_homewatts&metric=alert_status)](https://sonarcloud.io/dashboard?id=MarkusDunkel_homewatts)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=MarkusDunkel_homewatts&metric=coverage)](https://sonarcloud.io/dashboard?id=MarkusDunkel_homewatts)

## Why this project exists
- **Webanwendung zur Analyse von Heim-Photovoltaik-Daten** (PV-Daten in Echtzeit + Historie)
- **Architektur und Implementierung mit Spring Boot (Backend) und React (Frontend)**
- **Sicherer Demo-Zugriff**: signierte Tokens, Rate-Limiting, Audit-Logging
- **CI-Pipeline** mit automatisierten Tests, JaCoCo-Coverage & SonarCloud-Quality-Gates
- **Gestufte Deployments** mit versionierten Docker-Images via GitHub Actions

## What I’m showcasing
- **System design**: decoupled API + worker services with clear boundaries and scaling paths.
- **Security-first API design**: JWT-based sessions, refresh-token rotation, demo access controls.
- **Operational excellence**: containerized services, infrastructure automation, and observability hooks.
- **Frontend craft**: polished UX, typed APIs, reusable UI primitives, and data visualization.

## Infrastructure & Architecture
```mermaid
%%{init: { "theme": "base",
  "flowchart": { "curve": "basis", "htmlLabels": true },
  "themeVariables": {
    "primaryColor": "transparent",
    "lineColor": "hsl(var(--background))",
    "fontSize": "14px",
    "fontFamily": "Inter, sans-serif",
    "textColor": "hsl(var(--foreground))"
    }}}%%
graph TD

  B["<div style='text-align:center;font-size:17px;color:hsl(var(--foreground))'><b>Browser</b></div>"]

  RP["<div style='text-align:center;font-size:17px;color:hsl(var(--foreground));margin-bottom:5px'><b>Reverse Proxy</b></div>
  <div style='text-align:left;color:hsl(var(--foreground))'>Traefik<br/>TLS termination<br/>Routing</div>"]

  subgraph PR["<div style='padding-left:140px;color:hsl(var(--foreground));font-weight:700;white-space:nowrap'>PROD, STAGING</div>"]

  FE["<div style='text-align:center;font-size:17px;color:hsl(var(--foreground));margin-bottom:5px'><b>Frontend</b></div>
  <div style='text-align:left;color:hsl(var(--foreground))'>React · TypeScript · Tailwind<br/>nginx serves static SPA<br/>nginx proxies <code>/api/**</code> →<br/>
  <code>backend:8080</code></div>"]

  BE["<div style='text-align:center;font-size:17px;color:hsl(var(--foreground));margin-bottom:5px'><b>Backend</b></div>
  <div style='text-align:left;color:hsl(var(--foreground))'>Spring Boot API<br/>Spring Security · JPA · JWT</div>"]
  
  DB[("<div style='text-align:center;font-size:14px;color:hsl(var(--foreground))'><b>Postgres DB</b></div>")]
  end
  
  WRK["<div style='text-align:center;font-size:17px;color:hsl(var(--foreground));margin-bottom:5px'><b>Collector / Worker</b></div>
  <div style='text-align:left;color:hsl(var(--foreground))'>Spring Boot (non-web)<br/>scheduled jobs</div>"]
  
  DBC[("<div style='text-align:center;font-size:14px;color:hsl(var(--foreground))'><b>Postgres DB <br/>(shared cache)</b></div>")]
  
  EX["<div style='text-align:center;font-size:14px;color:hsl(var(--foreground))'><b>External SEMS API</b></div>"]
  
  %% --- FLOWS ---
  WRK --> DBC
  B <-->|HTTPS| RP
  RP -->|"/" static assets| FE
  FE -->|proxy /api/**| BE
  BE -->|JPA| DB
  WRK -->|WebClient| EX
  BE --> |JDBC| DBC
  

  %% --- CLASS DEFINITIONS ---
  classDef rounded fill:transparent,stroke:#555,stroke-width:1px,rx:5,ry:5
  class FE,BE,WRK,RP,B,EX rounded
  classDef db fill:transparent,stroke:#555,stroke-width:1px

  class DBC db
  class DB db

  style PR fill:transparent,stroke:#888,stroke-width:3px,stroke-dasharray:6 4

  %% --- STYLING ALL ARROWS ---
  linkStyle default stroke-width:2px,fill:none
```

## Key Features (selected)
### Secure demo access
- Signed demo tokens and rate-limited redemption for frictionless trials.
- Audit logging of all demo activations.
- Demo users inherit roles and are clearly identified in the UI.

### Data ingestion and analytics
- Dedicated **collector/worker** profile to ingest SEMS data on schedules.
- History + snapshot APIs for real-time and trending analytics.
- Separation of ingestion and API services for scaling and isolation.

### Frontend experience
- React + TypeScript SPA with responsive UI and data-rich dashboards.
- Recharts-powered visualizations for PV generation, load, and storage.
- Clean routing for authenticated, demo, and error experiences.

## Tech Stack
**Backend**
- Java 17, Spring Boot 3 (Web, Security, Data JPA, Validation, Actuator, WebFlux)
- PostgreSQL 15, Flyway, Resilience4j, Auth0 JWT
- Rate limiting: Bucket4j + Caffeine

**Frontend**
- React 18, TypeScript, Vite, TailwindCSS
- Zustand, React Router, Axios, Recharts, shadcn/ui

**Ops**
- Docker Compose + Traefik (TLS termination & routing)
- Nginx for SPA hosting and API proxying
- GitHub Actions, JaCoCo, SonarCloud
- Artifact Registry + GCE deployment playbook

## CI/CD & Quality
- Automated checks on pull requests.
- JaCoCo coverage reporting and SonarCloud quality gates.
- Versioned Docker images pushed via GitHub Actions workflows.
- Multi-stage deployment workflows (staging → production).

## Running locally
> Quick start (full stack via Docker):
```
docker compose up --build
```

Manual dev workflow:
- `backend/`: `mvn spring-boot:run`
- `frontend/`: `npm install && npm run dev`

## Project layout
```
├── backend/                # Spring Boot API + collector profile
├── frontend/               # Vite + React SPA
├── infrastructure/         # Deployment runbooks & infra notes
├── docker-compose.yml      # Traefik, API, worker, frontend, Postgres
└── README.md               # You are here
```

## Contact
If you’re looking for a developer who can design, build, and ship full-stack systems with production rigor, feel free to reach out.
