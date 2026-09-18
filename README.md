# CodeSense — AI-Powered Code Evaluation & Debugging Platform

> A learning-focused code evaluation platform that executes Java programs, detects compilation/runtime/timeout failures, explains errors using AI, stores evaluation history, and provides analytics through a modern web interface.

---

## 🚀 Overview

**CodeSense** is an AI-powered code evaluation and debugging platform developed as an academic Minor Project.

The system combines a Java-based execution engine with AI-assisted debugging, persistent evaluation history, analytics, REST APIs, and a modern web interface.

Unlike a basic compiler or online judge that only reports whether code failed, CodeSense helps students understand:

- What went wrong
- Where the failure occurred
- Why the failure happened
- What logical direction can be used to fix it
- How previous evaluations are performing over time

The AI layer is intentionally designed as a **diagnostic assistant**, not a complete code-generation tool.

---

## ✨ Key Features

### 🧪 Smart Code Evaluation

- Java source-code evaluation
- Automatic compilation using `javac`
- Controlled program execution
- Compile-error detection
- Runtime-exception detection
- Execution-timeout detection
- Standard output capture
- Error/stack-trace capture
- Execution duration measurement
- Unique evaluation IDs

---

### 🤖 AI-Powered Debugging

CodeSense uses AI to analyze failed evaluations and provide:

- Plain-language root-cause explanation
- Stage-aware debugging
- Runtime exception explanation
- Compile-error explanation
- Timeout/infinite-loop analysis
- Suggested debugging direction

The AI prompt is deliberately constrained to provide:

```text
HINT:
Root-cause explanation

SUGGESTED_FIX:
Logical direction for correction

It does not ask the AI to generate a complete corrected implementation.

This makes the system focused on learning and debugging, rather than simply copying AI-generated solutions.

💾 Persistent Evaluation History

CodeSense stores evaluation records using SQLite and JDBC.

Stored information includes:

Evaluation ID
Timestamp
Class name
Evaluation status
Exit code
Program output
Error output
AI diagnostic
AI suggested direction
AI request status
Execution duration

Users can retrieve:

Recent evaluations
Individual evaluations by ID
📊 Analytics

The analytics module calculates useful evaluation statistics such as:

Total evaluations
Successful evaluations
Compile errors
Runtime errors
Timeouts
Success rate
Failure rate
Average execution duration
AI requests
AI availability
AI unavailability

This allows CodeSense to provide insight beyond individual code execution.

🌐 REST API

The backend exposes a lightweight REST API built using Java's standard JDK HTTP server.

Method	Endpoint	Description
GET	/api/health	API health check
POST	/api/evaluate	Evaluate Java code
GET	/api/history	Retrieve recent evaluations
GET	/api/history/{id}	Retrieve a specific evaluation
GET	/api/analytics	Retrieve evaluation statistics

The web frontend communicates with the backend exclusively through these APIs.

🎨 Modern Web Interface

The CodeSense web application includes:

Evaluate interface
History interface
Analytics dashboard
Modern dark UI
Code-editor style input
Live backend connection status
Result/status cards
AI diagnostic cards
Loading states
Smooth transitions
Hover interactions
Responsive layout
Aurora theme
Ocean theme
Ember theme
Theme persistence

The interface is designed to feel modern and student-friendly while remaining suitable for academic demonstration.

🏗️ System Architecture
                         ┌──────────────────────┐
                         │     Web Frontend     │
                         │   HTML / CSS / JS    │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │      REST API        │
                         │  JDK HttpServer      │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │  SystemController    │
                         │ Queue + Coordination │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │   ExecutionEngine    │
                         │ Compile / Run /      │
                         │ Timeout Management   │
                         └──────────┬───────────┘
                                    │
                      ┌─────────────┴─────────────┐
                      ▼                           ▼
             ┌────────────────┐          ┌─────────────────┐
             │  AIConnector   │          │ EvaluationRecord│
             │ AI Diagnostics │          │ Unified Result  │
             └────────────────┘          └────────┬────────┘
                                                   │
                                                   ▼
                                          ┌────────────────┐
                                          │   SQLite DB    │
                                          └───────┬────────┘
                                                  │
                              ┌───────────────────┴──────────────────┐
                              ▼                                      ▼
                     ┌──────────────────┐                  ┌──────────────────┐
                     │ History Service  │                  │Analytics Service │
                     └──────────────────┘                  └──────────────────┘
🔄 Evaluation Flow

When a student submits Java code:

Java Source Code
       ↓
POST /api/evaluate
       ↓
Request Validation
       ↓
SystemController
       ↓
Evaluation Queue
       ↓
ExecutionEngine
       ↓
Compilation
       ↓
Program Execution
       ↓
┌───────────────┬────────────────┬─────────────────┐
│               │                │                 │
▼               ▼                ▼                 ▼
SUCCESS     COMPILE ERROR    RUNTIME ERROR      TIMEOUT
│               │                │                 │
└───────────────┴────────────────┴─────────────────┘
                        ↓
                 EvaluationRecord
                        ↓
                 AI Diagnostics
                        ↓
                  SQLite Storage
                        ↓
              History / Analytics
                        ↓
                  REST Response
                        ↓
                   Web UI
🧠 AI Diagnostic Philosophy

CodeSense deliberately avoids using AI as a generic chatbot.

The AI receives only the relevant debugging context:

Source code
Error trace/compiler output
Evaluation stage

The prompt instructs the AI to identify the root cause and explain the logical correction direction.

Example

For:

int a = 10;
int b = 0;

System.out.println(a / b);

CodeSense detects:

Runtime Error
java.lang.ArithmeticException: / by zero

The AI can then explain that the program attempts integer division by zero and provide a logical direction for handling the invalid divisor.

🛡️ Failure-Safe AI Design

AI is an enhancement layer, not a dependency for core evaluation.

If the AI provider fails because of:

Missing API key
Invalid API key
Network failure
Timeout
Rate limiting
Invalid response
Provider error

CodeSense still returns the normal compiler/runtime/timeout result.

                AI Available
                     │
                     ▼
             AI Diagnostic
                     │
                     ▼
             Complete Result


                AI Unavailable
                     │
                     ▼
          Core Evaluation Result
                     │
                     ▼
              Graceful Fallback

This ensures that a provider failure cannot break the primary code evaluation pipeline.

🔐 Security

The AI API key is never stored in the source code.

CodeSense reads the provider key from an environment variable:

SCE_AI_API_KEY

The production deployment stores the secret in the hosting environment rather than inside GitHub.

Security principles
No API key committed to Git
No API key inside Java source code
No API key inside frontend JavaScript
No API key inside README
Runtime environment configuration
Provider authentication through request headers

Never commit API keys, passwords, tokens, or other secrets to a public repository.

🗂️ Project Structure
Smart Evaluator/
│
├── src/com/sce/
│   ├── Main.java
│   ├── SystemController.java
│   │
│   ├── core/
│   │   ├── EvaluationRequest.java
│   │   ├── Result.java
│   │   └── EvaluationRecord.java
│   │
│   ├── engine/
│   │   ├── ExecutionEngine.java
│   │   ├── CompileResult.java
│   │   ├── ExecResult.java
│   │   ├── EvaluationException.java
│   │   └── Stage.java
│   │
│   ├── io/
│   │   └── FileHandler.java
│   │
│   ├── ai/
│   │   ├── AIConnector.java
│   │   └── AIResponse.java
│   │
│   ├── database/
│   │   ├── DatabaseManager.java
│   │   └── EvaluationRepository.java
│   │
│   ├── history/
│   │   ├── EvaluationHistoryException.java
│   │   └── EvaluationHistoryService.java
│   │
│   ├── analytics/
│   │   ├── EvaluationStatistics.java
│   │   └── EvaluationAnalytics.java
│   │
│   └── api/
│       ├── ApiConfig.java
│       ├── ApiServer.java
│       ├── ApiResponse.java
│       ├── JsonUtil.java
│       ├── HealthHandler.java
│       ├── EvaluateHandler.java
│       ├── HistoryHandler.java
│       ├── AnalyticsHandler.java
│       └── CorsHandler.java
│
├── docs/
│   ├── index.html
│   ├── css/
│   │   └── style.css
│   └── js/
│       └── app.js
│
├── lib/
│   ├── sqlite-jdbc-3.53.4.0.jar
│   └── sqlite-jdbc-3.53.4.0-natives-all.jar
│
├── .vscode/
│   └── settings.json
│
├── Dockerfile
├── .gitignore
└── README.md
🔧 Technology Stack
Layer	Technology
Programming Language	Java 17
Backend	Core Java
REST API	com.sun.net.httpserver.HttpServer
Execution	javac / java
Database	SQLite
Database Access	JDBC
AI	Gemini REST API
Frontend	HTML5 / CSS3 / JavaScript
Containerization	Docker
Backend Hosting	Render
Frontend Hosting	GitHub Pages
Version Control	Git / GitHub
Development	VS Code
🧩 Design Principles

CodeSense was designed with clear separation of responsibilities.

Core Layer

The core layer contains evaluation requests, results, and unified evaluation records.

Execution Layer

ExecutionEngine handles:

Compilation
Program execution
Timeout monitoring
Process management
Output/error capture
Controller Layer

SystemController owns:

Evaluation queue
Evaluation dispatch
Worker flow
Correlation of REST requests with exact evaluation results
AI Layer

AIConnector handles only AI-provider communication and diagnostic parsing.

Persistence Layer

EvaluationRepository manages SQLite persistence.

History Layer

EvaluationHistoryService provides controlled access to stored evaluations.

Analytics Layer

EvaluationAnalytics converts evaluation records into statistics.

API Layer

The REST API exposes the system to external clients without duplicating the core evaluation engine.

Presentation Layer

The web frontend consumes the REST API and contains no core evaluation logic.

🐳 Docker Deployment

CodeSense uses a multi-stage Docker build.

Build Stage
Java 17 JDK
      ↓
Source Code
      ↓
SQLite JDBC
      ↓
javac compilation
      ↓
Compiled application
Runtime Stage
Java Runtime
      ↓
Compiled application
      ↓
SQLite JDBC
      ↓
com.sce.Main

The application reads the hosting platform's PORT environment variable, allowing the same application to run locally and in cloud deployment.

☁️ Cloud Deployment

The project uses:

GitHub
   ↓
Docker Build
   ↓
Render
   ↓
Live Java REST API
   ↓
GitHub Pages
   ↓
Web Frontend
Production Components

Frontend

GitHub Pages

Backend

Render

Container

Docker

Database

SQLite

AI Configuration

Environment variable

▶️ Run Locally
Requirements
JDK 17
Git
VS Code or another Java IDE
SQLite JDBC driver

The SQLite JDBC driver is already included in:

lib/
Compile

From the project root:

Remove-Item -Recurse -Force out -ErrorAction SilentlyContinue

New-Item -ItemType Directory out

$files = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }

javac -cp "lib\sqlite-jdbc-3.53.4.0.jar" -d out $files
Configure AI

Set the environment variable:

SCE_AI_API_KEY

with your provider API key.

Do not place the actual key inside the source code.

Start Backend
java -cp "out;lib\sqlite-jdbc-3.53.4.0.jar" com.sce.Main

The REST API will run locally using the configured application port.

🧪 Testing

The project was developed using incremental testing across the major architectural layers.

Testing covers:

Successful Java execution
Compilation failure
Runtime exception
Timeout/infinite loop
AI diagnostic generation
SQLite persistence
History retrieval
Analytics calculations
REST API endpoints
API error handling
API integration
End-to-end web evaluation
Docker deployment
Live cloud deployment
Environment-based AI configuration
📈 Development Roadmap
Completed
 Core Java evaluation engine
 Compilation and execution
 Runtime error detection
 Timeout detection
 AI diagnostic integration
 Unified EvaluationRecord
 SQLite persistence
 Evaluation history
 Analytics/statistics
 REST API
 Modern web frontend
 Multiple UI themes
 Docker deployment
 Render backend deployment
 GitHub Pages frontend deployment
 Secure environment-based AI API configuration
 Live end-to-end testing
Future Enhancements
 Multi-language evaluation support
 Language-specific execution strategies
 Advanced code-quality metrics
 User authentication
 User-specific evaluation history
 More advanced analytics
 Additional AI-assisted learning features
🎓 Academic Relevance

CodeSense demonstrates the practical application of multiple Computer Science concepts:

Object-Oriented Programming
Software Engineering
Exception Handling
Process Management
Concurrency
REST API Design
Database Management
JDBC
AI Integration
Web Development
Docker
Cloud Deployment
Layered Architecture
System Design

The project demonstrates how these concepts can be combined into a complete software system.

💡 Why CodeSense?

A traditional code evaluator may simply report:

❌ Compilation Failed

CodeSense extends that workflow:

❌ Evaluation Failed
        ↓
🔎 Failure Stage
        ↓
📋 Compiler / Runtime Evidence
        ↓
🤖 AI Root-Cause Explanation
        ↓
💡 Suggested Debugging Direction
        ↓
💾 Evaluation History
        ↓
📊 Analytics

The objective is not only to determine whether code works, but to help students understand why it does not work.

📸 Screenshots

Recommended screenshots for the repository:

screenshots/
├── evaluate.png
├── runtime-error.png
├── ai-diagnostic.png
├── history.png
├── analytics.png
└── themes.png

Example Markdown:

## Screenshots

### Evaluate

![CodeSense Evaluate](screenshots/evaluate.png)

### AI Diagnostic

![CodeSense AI Diagnostic](screenshots/ai-diagnostic.png)

### History

![CodeSense History](screenshots/history.png)

### Analytics

![CodeSense Analytics](screenshots/analytics.png)
👨‍💻 Project Information

Project: CodeSense

Title: AI-Powered Code Evaluation & Debugging Platform

Domain: AI + Education / EdTech / Developer Tooling

Project Type: Academic Minor Project

Primary Language: Java

Frontend: HTML, CSS, JavaScript

Database: SQLite

API: REST

Deployment: Docker + Render + GitHub Pages

🔒 Project Status
CORE ENGINE        ✅ Complete
AI DEBUGGING       ✅ Complete
DATABASE           ✅ Complete
HISTORY            ✅ Complete
ANALYTICS          ✅ Complete
REST API           ✅ Complete
WEB FRONTEND       ✅ Complete
DOCKER             ✅ Complete
CLOUD DEPLOYMENT   ✅ Complete
LIVE TESTING       ✅ Complete
📄 License

This project was developed for academic and educational purposes.

⭐ Acknowledgement

CodeSense was developed as a learning-focused software engineering project to explore the integration of:

Java Execution Systems + AI-Assisted Debugging + SQLite Persistence + REST APIs + Analytics + Modern Web Development + Docker + Cloud Deployment

into a single end-to-end software platform.
