# StudentHUB

StudentHUB to organizer studiów na Androida. Łączy plan lekcji i zjazdów, zaliczenia, płatności, przypomnienia, widżet oraz pocztę studencką Politechniki Świętokrzyskiej.

## Instalacja

Pobierz plik APK z [GitHub Releases](../../releases). Aplikacja wymaga Androida 8.0 (API 26) lub nowszego.

- Plan można prowadzić ręcznie albo zaimportować z linku iCalendar USOS PŚk lub pliku ICS.
- Plany i wpisy zapisują się na urządzeniu. Kopię wyeksportujesz w **Ustawienia → Kopia zapasowa**.
- Integracja pocztowa używa połączeń IMAP i SMTP z serwerem uczelni. Jeśli włączysz sprawdzanie poczty w tle, dane logowania zostaną zaszyfrowane kluczem danego urządzenia.

## Budowanie

Otwórz katalog repozytorium w Android Studio. Użyj JDK 17 oraz Android SDK Platform 35. Z terminala uruchom:

```sh
./gradlew assembleDebug
```

W Windows użyj `gradlew.bat assembleDebug`. APK trafi do `app/build/outputs/apk/debug/`. Do podpisania aktualizacji na wcześniejszą instalację użyj oryginalnego klucza podpisu. Przechowuj go poza repozytorium.

Pierwsze publiczne wydanie ma tag `v1.0.0`. Aplikacja zachowuje wersję instalacyjną 1.11.3 zgodną z wcześniejszymi kompilacjami.

## Licencja

Kod StudentHUB jest udostępniony na [licencji MIT](LICENSE). Informacje o licencjach bibliotek Android Mail i Android Activation są w [zasobach aplikacji](app/src/main/assets/licenses/).
