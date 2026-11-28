import 'dart:typed_data';
import 'package:intl/intl.dart';

const statuses = [
  'Pending',
  'Accepted',
  'Printing',
  'Ready for Pickup',
  'Completed',
  'Rejected',
];
String money(num value) => '₱${value.toStringAsFixed(2)}';
String dateLabel(DateTime date) =>
    DateFormat('MMM d, h:mm a').format(date.toLocal());
String dayKey(DateTime date) =>
    DateFormat('yyyy-MM-dd').format(date.toUtc().add(const Duration(hours: 8)));

class PrintOptions {
  final String paper;
  final int pages, copies;
  final bool color, duplex;
  const PrintOptions({
    this.paper = 'A4',
    this.pages = 1,
    this.copies = 1,
    this.color = false,
    this.duplex = false,
  });
  int get rate => (color ? 10 : 2) + (paper == 'Legal' ? 1 : 0);
  // Charged per printed side. Duplex saves sheets, not printed sides.
  int get total => pages * copies * rate;
  int get sheets => (duplex ? (pages / 2).ceil() : pages) * copies;
  Map<String, dynamic> toMap() => {
    'paper': paper,
    'pages': pages,
    'copies': copies,
    'color': color,
    'duplex': duplex,
  };
  factory PrintOptions.fromMap(Map<String, dynamic> m) => PrintOptions(
    paper: m['paper'],
    pages: m['pages'],
    copies: m['copies'],
    color: m['color'],
    duplex: m['duplex'],
  );
}

class SelectedPdf {
  final String name;
  final Uint8List bytes;
  final int pages;
  const SelectedPdf(this.name, this.bytes, this.pages);
}

class PrintOrder {
  final String id, ownerId, studentName, fileName, storagePath, status;
  final PrintOptions options;
  final DateTime createdAt;
  final int? queue;
  final String? queueDay;
  final String note;
  const PrintOrder({
    required this.id,
    required this.ownerId,
    required this.studentName,
    required this.fileName,
    required this.storagePath,
    required this.options,
    required this.createdAt,
    this.status = 'Pending',
    this.queue,
    this.queueDay,
    this.note = '',
  });
  PrintOrder update(
    String status, {
    int? queue,
    String? queueDay,
    String? note,
  }) => PrintOrder(
    id: id,
    ownerId: ownerId,
    studentName: studentName,
    fileName: fileName,
    storagePath: storagePath,
    options: options,
    createdAt: createdAt,
    status: status,
    queue: queue ?? this.queue,
    queueDay: queueDay ?? this.queueDay,
    note: note ?? this.note,
  );
  Map<String, dynamic> toMap() => {
    'ownerId': ownerId,
    'studentName': studentName,
    'fileName': fileName,
    'storagePath': storagePath,
    'options': options.toMap(),
    'estimatedCost': options.total,
    'createdAt': createdAt.millisecondsSinceEpoch,
    'status': status,
    'queue': queue,
    'queueDay': queueDay,
    'note': note,
  };
  factory PrintOrder.fromMap(String id, Map<String, dynamic> m) => PrintOrder(
    id: id,
    ownerId: m['ownerId'],
    studentName: m['studentName'],
    fileName: m['fileName'],
    storagePath: m['storagePath'],
    options: PrintOptions.fromMap(Map<String, dynamic>.from(m['options'])),
    createdAt: DateTime.fromMillisecondsSinceEpoch(m['createdAt']),
    status: m['status'],
    queue: m['queue'],
    queueDay: m['queueDay'],
    note: m['note'] ?? '',
  );
}

bool canTransition(String from, String to) => switch (from) {
  'Pending' => to == 'Accepted' || to == 'Rejected',
  'Accepted' => to == 'Printing',
  'Printing' => to == 'Ready for Pickup',
  'Ready for Pickup' => to == 'Completed',
  _ => false,
};
