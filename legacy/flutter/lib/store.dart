import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_storage/firebase_storage.dart';
import 'models.dart';

class AppStore extends ChangeNotifier {
  AppStore({required this.demo});
  final bool demo;
  String? uid;
  String name = '', email = '';
  bool admin = false, busy = false;
  double progress = 0;
  String? error;
  List<PrintOrder> orders = [];
  final Map<String, Uint8List> _demoFiles = {};
  StreamSubscription<QuerySnapshot<Map<String, dynamic>>>? _subscription;
  final List<PrintOrder> _demoOrders = [
    PrintOrder(
      id: 'PE-1042',
      ownerId: 'student',
      studentName: 'Alex Student',
      fileName: 'Research proposal.pdf',
      storagePath: '',
      options: const PrintOptions(pages: 12, copies: 2),
      createdAt: DateTime.now().subtract(const Duration(minutes: 22)),
      status: 'Printing',
      queue: 1,
      queueDay: dayKey(DateTime.now()),
    ),
    PrintOrder(
      id: 'PE-1043',
      ownerId: 'student',
      studentName: 'Alex Student',
      fileName: 'Laboratory activity.pdf',
      storagePath: '',
      options: const PrintOptions(pages: 4, color: true),
      createdAt: DateTime.now().subtract(const Duration(minutes: 8)),
    ),
  ];
  Future<void> authenticate(
    String email,
    String password, {
    String? registrationName,
  }) async {
    await run(() async {
      if (demo) {
        uid = 'student';
        name = registrationName ?? 'Alex Student';
        this.email = email;
        admin = false;
        _refreshDemo();
        return;
      }
      UserCredential credential;
      if (registrationName != null) {
        credential = await FirebaseAuth.instance.createUserWithEmailAndPassword(
          email: email,
          password: password,
        );
        await credential.user!.updateDisplayName(registrationName);
      } else {
        credential = await FirebaseAuth.instance.signInWithEmailAndPassword(
          email: email,
          password: password,
        );
      }
      final user = credential.user!;
      uid = user.uid;
      this.email = user.email ?? '';
      name = user.displayName ?? email.split('@').first;
      final claims = await user.getIdTokenResult(true);
      admin = claims.claims?['admin'] == true;
      await _subscription?.cancel();
      Query<Map<String, dynamic>> query = FirebaseFirestore.instance.collection(
        'orders',
      );
      if (!admin) query = query.where('ownerId', isEqualTo: uid);
      _subscription = query.snapshots().listen(
        (snapshot) {
          orders =
              snapshot.docs
                  .map((d) => PrintOrder.fromMap(d.id, d.data()))
                  .toList()
                ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
          error = null;
          notifyListeners();
        },
        onError: (Object e) {
          error = 'Unable to sync orders: $e';
          notifyListeners();
        },
      );
    });
  }

  void demoLogin(bool asAdmin) {
    uid = asAdmin ? 'admin' : 'student';
    admin = asAdmin;
    name = asAdmin ? 'Campus Print Shop' : 'Alex Student';
    email = asAdmin ? 'admin@demo.local' : 'student@demo.local';
    error = null;
    _refreshDemo();
  }

  void _refreshDemo() {
    orders = _demoOrders.where((o) => admin || o.ownerId == uid).toList()
      ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
    notifyListeners();
  }

  Future<void> signOut() async {
    await _subscription?.cancel();
    if (!demo) await FirebaseAuth.instance.signOut();
    uid = null;
    orders = [];
    admin = false;
    error = null;
    notifyListeners();
  }

  Future<void> run(Future<void> Function() action) async {
    busy = true;
    error = null;
    notifyListeners();
    try {
      await action();
    } catch (e) {
      error = e is FirebaseAuthException ? (e.message ?? e.code) : e.toString();
      rethrow;
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<void> submit(SelectedPdf pdf, PrintOptions options) => run(() async {
    if (uid == null || admin) {
      throw StateError('Sign in as a student to submit.');
    }
    if (pdf.bytes.length > 5 * 1024 * 1024 || pdf.pages != options.pages) {
      throw StateError('Invalid document.');
    }
    final id = demo
        ? 'PE-${DateTime.now().microsecondsSinceEpoch}'
        : FirebaseFirestore.instance.collection('orders').doc().id;
    final path = 'documents/$uid/$id.pdf';
    if (!demo) {
      final task = FirebaseStorage.instance
          .ref(path)
          .putData(pdf.bytes, SettableMetadata(contentType: 'application/pdf'));
      final sub = task.snapshotEvents.listen((s) {
        progress = s.totalBytes == 0 ? 0 : s.bytesTransferred / s.totalBytes;
        notifyListeners();
      });
      try {
        await task;
      } finally {
        await sub.cancel();
      }
    }
    final order = PrintOrder(
      id: id,
      ownerId: uid!,
      studentName: name,
      fileName: pdf.name,
      storagePath: path,
      options: options,
      createdAt: DateTime.now(),
    );
    if (demo) {
      _demoOrders.add(order);
      _demoFiles[id] = pdf.bytes;
      _refreshDemo();
    } else {
      // Reuse a single ID for this attempt; clean up uploaded data if order creation fails.
      try {
        await FirebaseFirestore.instance
            .collection('orders')
            .doc(id)
            .set(order.toMap());
      } catch (_) {
        try {
          await FirebaseStorage.instance.ref(path).delete();
        } catch (_) {}
        rethrow;
      }
    }
    progress = 0;
  });
  Future<void> updateStatus(
    PrintOrder order,
    String next, {
    String note = '',
  }) => run(() async {
    if (!admin || !canTransition(order.status, next)) {
      throw StateError('Invalid status change.');
    }
    if (next == 'Rejected' && note.trim().isEmpty) {
      throw StateError('Please give a reason for rejecting the order.');
    }
    final day = dayKey(DateTime.now());
    if (demo) {
      final index = _demoOrders.indexWhere((o) => o.id == order.id);
      final queues = _demoOrders
          .where((o) => o.queueDay == day)
          .map((o) => o.queue ?? 0);
      final number = queues.fold<int>(0, (a, b) => a > b ? a : b) + 1;
      _demoOrders[index] = order.update(
        next,
        queue: next == 'Accepted' ? number : null,
        queueDay: next == 'Accepted' ? day : null,
        note: note,
      );
      _refreshDemo();
      return;
    }
    final db = FirebaseFirestore.instance;
    await db.runTransaction((tx) async {
      final ref = db.collection('orders').doc(order.id);
      final current = await tx.get(ref);
      if (!current.exists || current.data()!['status'] != order.status) {
        throw StateError('Order changed. Refresh and try again.');
      }
      final updates = <String, dynamic>{'status': next, 'note': note};
      if (next == 'Accepted') {
        final counter = db.collection('counters').doc(day);
        final snapshot = await tx.get(counter);
        final number = ((snapshot.data()?['last'] ?? 0) as int) + 1;
        tx.set(counter, {'last': number});
        updates.addAll({'queue': number, 'queueDay': day});
      }
      tx.update(ref, updates);
    });
  });
  Future<Uint8List> documentBytes(PrintOrder order) async {
    if (demo) {
      final bytes = _demoFiles[order.id];
      if (bytes == null) {
        throw StateError(
          'This sample order has no document. Submit your own PDF to preview it.',
        );
      }
      return bytes;
    }
    final bytes = await FirebaseStorage.instance
        .ref(order.storagePath)
        .getData(5 * 1024 * 1024);
    if (bytes == null) throw StateError('Document is unavailable.');
    return bytes;
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}
