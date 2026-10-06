# Contributing to Essential Moments

Thank you for your interest in contributing to **Essential Moments**! We welcome contributions, bug reports, and suggestions from developers of all skill levels.

## How to Contribute

### Reporting Bugs
Before creating a new bug report, please check existing [Issues](../../issues) to see if it has already been reported.

When creating an issue, please use our [Bug Report Template](ISSUE_TEMPLATE/bug_report.md) and include:
* Android OS version and device model
* App version or commit hash
* Steps to reproduce the bug
* Expected vs actual behavior
* Relevant logcat output if applicable

### Suggesting Enhancements
Feature requests are welcomed! Use our [Feature Request Template](ISSUE_TEMPLATE/feature_request.md) to describe:
* The problem you want solved
* Your proposed solution or user experience
* Any relevant technical context (e.g. model optimization, UI/UX improvement)

---

## Development Setup

1. **Prerequisites**:
   * Android Studio (Ladybug or newer recommended)
   * JDK 17
   * Android SDK (API Level 24 minimum, target API 34+)

2. **Clone and Build**:
   ```bash
   git clone https://github.com/theerthkr/EssentialMoments.git
   cd EssentialMoments
   ./gradlew assembleDebug
   ```

3. **Branching Guidelines**:
   * Create a feature branch with a descriptive name:
     ```bash
     git checkout -b feature/your-feature-name
     # or
     git checkout -b fix/issue-description
     ```
   * Make focused, atomic commits following conventional commit messages (`feat: ...`, `fix: ...`, `docs: ...`).

4. **Code Quality**:
   * Write idiomatic Kotlin following Android guidelines.
   * Format your code before submitting:
     ```bash
     ./gradlew lint
     ```

5. **Submitting a Pull Request**:
   * Push your branch to your fork.
   * Open a Pull Request against `master`.
   * Fill out the PR template completely.

---

## Code of Conduct

Please note that this project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.
