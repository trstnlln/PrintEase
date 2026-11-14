import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'store.dart';
import 'firebase_options.dart';
import 'screens.dart';

const ink = Color(0xFF172B29);
const green = Color(0xFF176B51);
const cream = Color(0xFFF7F8F3);
const demoMode = bool.fromEnvironment('DEMO', defaultValue: true);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  String? setupError;
  if (!demoMode) {
    try {
      await Firebase.initializeApp(
        options: DefaultFirebaseOptions.currentPlatform,
      );
    } catch (_) {
      setupError =
          'Firebase is not configured. Follow README.md to connect your Firebase Android app, or run with --dart-define=DEMO=true.';
    }
  }
  runApp(
    PrintEaseApp(
      store: AppStore(demo: demoMode),
      setupError: setupError,
    ),
  );
}

class PrintEaseApp extends StatelessWidget {
  const PrintEaseApp({super.key, required this.store, this.setupError});
  final AppStore store;
  final String? setupError;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'PrintEase',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      scaffoldBackgroundColor: cream,
      colorScheme: ColorScheme.fromSeed(
        seedColor: green,
        primary: green,
        surface: Colors.white,
        onSurface: ink,
      ),
      fontFamily: 'Roboto',
      appBarTheme: const AppBarTheme(
        backgroundColor: cream,
        foregroundColor: ink,
        centerTitle: false,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFFF2F5F1),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide.none,
        ),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 18,
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 52),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: const BorderSide(color: Color(0xFFE4E9E1)),
        ),
      ),
    ),
    home: setupError != null
        ? Scaffold(
            body: Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Text(setupError!),
              ),
            ),
          )
        : ListenableBuilder(
            listenable: store,
            builder: (context, _) => store.uid == null
                ? AuthScreen(store: store)
                : HomeScreen(store: store),
          ),
  );
}
