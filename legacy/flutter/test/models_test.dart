import 'package:flutter_test/flutter_test.dart';
import 'package:printease/models.dart';

void main() {
  test('Pricing matches independent shop examples', () {
    expect(const PrintOptions(pages: 12, copies: 2).total, 48);
    expect(
      const PrintOptions(
        paper: 'Legal',
        pages: 3,
        copies: 2,
        color: true,
      ).total,
      66,
    );
    expect(const PrintOptions(pages: 5, copies: 2, duplex: true).sheets, 6);
    expect(const PrintOptions(pages: 5, copies: 2, duplex: true).total, 20);
  });
  test('Queue days use Philippine time across UTC midnight', () {
    expect(dayKey(DateTime.utc(2026, 11, 9, 16)), '2026-11-10');
    expect(dayKey(DateTime.utc(2026, 11, 9, 15, 59)), '2026-11-09');
  });
  test('Orders cannot skip steps or reopen terminal statuses', () {
    expect(canTransition('Pending', 'Printing'), isFalse);
    expect(canTransition('Pending', 'Rejected'), isTrue);
    expect(canTransition('Accepted', 'Printing'), isTrue);
    expect(canTransition('Completed', 'Pending'), isFalse);
    expect(canTransition('Rejected', 'Accepted'), isFalse);
  });
}
