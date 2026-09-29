# Contributing

Thanks for your interest in PicPocket! Here's how to get involved.

## Bug Reports

Open a [GitHub issue](https://github.com/pipolarbear/picpocket/issues/new) with:

- Device model and Android version
- Steps to reproduce
- Expected vs actual behavior
- Screenshots or logcat output (if applicable)

## Feature Requests

Open a [GitHub issue](https://github.com/pipolarbear/picpocket/issues/new) with:

- What the feature does and why it's useful
- Any relevant examples or references

## Pull Requests

1. Fork the repository
2. Create a branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Run tests: `./gradlew testDebugUnitTest`
5. Commit with a [conventional commit](https://www.conventionalcommits.org/) message:

   ```
   feat: add page rotation support
   fix: crash when scanning blank page
   refactor: extract PDF generation logic
   ```

6. Push and open a PR against `main`

### Code Style

- Follow existing patterns in the codebase
- Kotlin — use idiomatic style, no comments on trivial code
- Compose — follow Material3 conventions, prefer `remember` over raw state
- MVVM — ViewModels own UI state via `StateFlow`, screens are stateless composables
- Hilt — use `@HiltViewModel` and `@Inject constructor`
- No hardcoded strings in composables — use string resources

### Before Submitting

- `./gradlew testDebugUnitTest` — the unit suite must pass
- `./gradlew assembleDebug` — must build clean
- Verify on a physical device if your change touches camera, PDF, or SAF logic (emulator limitations)
