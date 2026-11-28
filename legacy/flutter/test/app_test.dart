import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:printease/main.dart';
import 'package:printease/store.dart';

void main() {
  testWidgets('Student and admin share updates in demo mode', (tester) async {
    final store = AppStore(demo: true);
    await tester.pumpWidget(PrintEaseApp(store: store));
    await tester.ensureVisible(find.text('Try as student'));
    await tester.tap(find.text('Try as student'));
    await tester.pumpAndSettle();
    expect(find.text('Hello, Alex.'), findsOneWidget);
    expect(find.text('Start a print request'), findsOneWidget);
    store.demoLogin(true);
    await tester.pumpAndSettle();
    final pending = store.orders.firstWhere((o) => o.status == 'Pending');
    await store.updateStatus(pending, 'Accepted');
    await tester.pumpAndSettle();
    expect(store.orders.firstWhere((o) => o.id == pending.id).queue, 2);
    store.demoLogin(false);
    await tester.pumpAndSettle();
    expect(
      store.orders.firstWhere((o) => o.id == pending.id).status,
      'Accepted',
    );
    await tester.pumpWidget(const SizedBox());
    store.dispose();
  });
  test('Accepted demo orders have distinct queue numbers', () async {
    final store = AppStore(demo: true)..demoLogin(true);
    final pending = store.orders.firstWhere((o) => o.status == 'Pending');
    await store.updateStatus(pending, 'Accepted');
    final numbers = store.orders
        .where((o) => o.queue != null)
        .map((o) => o.queue)
        .toList();
    expect(numbers.toSet().length, numbers.length);
    store.dispose();
  });
}
