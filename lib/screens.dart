import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:pdfrx/pdfrx.dart';
import 'models.dart';
import 'store.dart';
import 'main.dart';

void showError(BuildContext context, Object error) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(error.toString()),
      behavior: SnackBarBehavior.floating,
    ),
  );
}

Widget brand({bool small = false}) => Row(
  mainAxisSize: MainAxisSize.min,
  children: [
    Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: green,
        borderRadius: BorderRadius.circular(13),
      ),
      child: Icon(
        Icons.print_rounded,
        color: Colors.white,
        size: small ? 20 : 27,
      ),
    ),
    const SizedBox(width: 11),
    Text(
      'PrintEase',
      style: TextStyle(
        fontSize: small ? 22 : 28,
        fontWeight: FontWeight.w800,
        letterSpacing: -1,
        color: ink,
      ),
    ),
  ],
);
Widget eyebrow(String text) => Text(
  text,
  style: const TextStyle(
    fontSize: 11,
    letterSpacing: 1.8,
    fontWeight: FontWeight.w700,
    color: green,
  ),
);
Widget panel(Widget child, {Color? color}) => Card(
  color: color,
  margin: EdgeInsets.zero,
  child: Padding(padding: const EdgeInsets.all(22), child: child),
);

class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key, required this.store});
  final AppStore store;
  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final form = GlobalKey<FormState>();
  final email = TextEditingController(),
      password = TextEditingController(),
      name = TextEditingController();
  bool register = false, obscure = true;
  @override
  void dispose() {
    email.dispose();
    password.dispose();
    name.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    if (!form.currentState!.validate()) return;
    try {
      await widget.store.authenticate(
        email.text.trim(),
        password.text,
        registrationName: register ? name.text.trim() : null,
      );
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 440),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                brand(),
                const SizedBox(height: 44),
                eyebrow('MADE FOR YOUR CAMPUS'),
                const SizedBox(height: 12),
                const Text(
                  'Less waiting.\nMore living.',
                  style: TextStyle(
                    fontSize: 44,
                    height: 1.08,
                    letterSpacing: -1.8,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 16),
                const Text(
                  'Your documents, printed on your schedule.\nSubmit. Track. Pick up.',
                  style: TextStyle(
                    fontSize: 16,
                    height: 1.6,
                    color: Color(0xFF64736B),
                  ),
                ),
                const SizedBox(height: 30),
                panel(
                  Form(
                    key: form,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          register ? 'Create your account' : 'Welcome back',
                          style: const TextStyle(
                            fontSize: 23,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'BSU Alangilan · Campus Print Shop',
                          style: TextStyle(color: Colors.black54),
                        ),
                        const SizedBox(height: 22),
                        if (register) ...[
                          TextFormField(
                            controller: name,
                            decoration: const InputDecoration(
                              labelText: 'Full name',
                            ),
                            validator: (v) => (v ?? '').trim().isEmpty
                                ? 'Enter your name'
                                : null,
                          ),
                          const SizedBox(height: 14),
                        ],
                        TextFormField(
                          controller: email,
                          keyboardType: TextInputType.emailAddress,
                          decoration: const InputDecoration(
                            labelText: 'Email address',
                            prefixIcon: Icon(Icons.alternate_email),
                          ),
                          validator: (v) =>
                              RegExp(
                                r'^[^\s@]+@[^\s@]+\.[^\s@]+$',
                              ).hasMatch(v ?? '')
                              ? null
                              : 'Enter a valid email',
                        ),
                        const SizedBox(height: 14),
                        TextFormField(
                          controller: password,
                          obscureText: obscure,
                          decoration: InputDecoration(
                            labelText: 'Password',
                            prefixIcon: const Icon(Icons.lock_outline),
                            suffixIcon: IconButton(
                              onPressed: () =>
                                  setState(() => obscure = !obscure),
                              icon: Icon(
                                obscure
                                    ? Icons.visibility_outlined
                                    : Icons.visibility_off_outlined,
                              ),
                            ),
                          ),
                          validator: (v) => (v?.length ?? 0) < 6
                              ? 'Use at least 6 characters'
                              : null,
                        ),
                        const SizedBox(height: 22),
                        FilledButton(
                          onPressed: widget.store.busy ? null : submit,
                          child: Text(
                            widget.store.busy
                                ? 'Please wait…'
                                : register
                                ? 'Create account'
                                : 'Sign in',
                          ),
                        ),
                        TextButton(
                          onPressed: widget.store.busy
                              ? null
                              : () => setState(() => register = !register),
                          child: Text(
                            register
                                ? 'Already registered? Sign in'
                                : 'New here? Create an account',
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                if (widget.store.demo) ...[
                  const SizedBox(height: 22),
                  panel(
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        eyebrow('EXPLORE THE PROTOTYPE'),
                        const SizedBox(height: 8),
                        const Text(
                          'Demo data stays in memory. Switch roles to try the full printing flow.',
                        ),
                        const SizedBox(height: 14),
                        OutlinedButton.icon(
                          onPressed: () => widget.store.demoLogin(false),
                          icon: const Icon(Icons.school_outlined),
                          label: const Text('Try as student'),
                        ),
                        const SizedBox(height: 8),
                        OutlinedButton.icon(
                          onPressed: () => widget.store.demoLogin(true),
                          icon: const Icon(Icons.storefront_outlined),
                          label: const Text('Try as administrator'),
                        ),
                      ],
                    ),
                  ),
                ],
                const SizedBox(height: 24),
                const Center(
                  child: Text(
                    'A little less queue. A little more you.',
                    style: TextStyle(color: Colors.black45, fontSize: 12),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.store});
  final AppStore store;
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int tab = 0;
  String filter = 'All';
  AppStore get store => widget.store;
  @override
  Widget build(BuildContext context) {
    final active = store.orders
        .where((o) => o.status != 'Completed' && o.status != 'Rejected')
        .length;
    final ready = store.orders
        .where((o) => o.status == 'Ready for Pickup')
        .length;
    final filtered = store.orders
        .where(
          (o) =>
              filter == 'All' ||
              (filter == 'Active'
                  ? !['Completed', 'Rejected'].contains(o.status)
                  : o.status == filter),
        )
        .toList();
    return Scaffold(
      appBar: AppBar(
        title: brand(small: true),
        actions: [
          if (store.demo)
            const Padding(
              padding: EdgeInsets.only(right: 8),
              child: Chip(
                label: Text(
                  'DEMO',
                  style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold),
                ),
              ),
            ),
          IconButton(
            tooltip: 'Sign out',
            onPressed: store.busy
                ? null
                : () async {
                    try {
                      await store.signOut();
                    } catch (e) {
                      if (context.mounted) showError(context, e);
                    }
                  },
            icon: const Icon(Icons.logout_rounded),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 920),
            child: ListView(
              padding: const EdgeInsets.fromLTRB(22, 24, 22, 100),
              children: [
                eyebrow(
                  store.admin ? 'SHOP WORKSPACE' : 'BSU ALANGILAN CAMPUS',
                ),
                const SizedBox(height: 10),
                Text(
                  store.admin
                      ? 'Let’s keep things moving.'
                      : 'Hello, ${store.name.split(' ').first}.',
                  style: const TextStyle(
                    fontSize: 30,
                    letterSpacing: -1,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  store.admin
                      ? 'A clear queue. A smoother day.'
                      : 'Good things come to those who skip the line.',
                  style: const TextStyle(
                    color: Color(0xFF64736B),
                    fontSize: 15,
                  ),
                ),
                const SizedBox(height: 26),
                if (tab == 2)
                  ..._account()
                else ...[
                  if (tab == 0) ...[
                    if (!store.admin)
                      panel(
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Icon(
                              Icons.description_outlined,
                              color: Colors.white,
                              size: 34,
                            ),
                            const SizedBox(height: 18),
                            const Text(
                              'Your next print,\njust a tap away.',
                              style: TextStyle(
                                fontSize: 28,
                                height: 1.15,
                                fontWeight: FontWeight.bold,
                                color: Colors.white,
                              ),
                            ),
                            const SizedBox(height: 12),
                            const Text(
                              'Upload a PDF. Choose your preferences.\nWe’ll take it from there.',
                              style: TextStyle(
                                color: Color(0xFFCCE3D3),
                                height: 1.6,
                              ),
                            ),
                            const SizedBox(height: 22),
                            FilledButton.icon(
                              style: FilledButton.styleFrom(
                                backgroundColor: const Color(0xFFDDF493),
                                foregroundColor: ink,
                              ),
                              onPressed: () => _newOrder(context),
                              icon: const Icon(Icons.add),
                              label: const Text('Start a print request'),
                            ),
                          ],
                        ),
                        color: green,
                      ),
                    if (!store.admin) const SizedBox(height: 18),
                    Row(
                      children: [
                        Expanded(
                          child: _stat(
                            'Active orders',
                            '$active',
                            Icons.layers_outlined,
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: _stat(
                            'Ready for pickup',
                            '$ready',
                            Icons.check_circle_outline,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 26),
                  ],
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          store.admin
                              ? 'Print queue'
                              : tab == 1
                              ? 'Your orders'
                              : 'Recent activity',
                          style: const TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                      if (!store.admin && tab == 1)
                        IconButton(
                          tooltip: 'New print request',
                          onPressed: () => _newOrder(context),
                          icon: const Icon(Icons.add_circle_outline),
                        ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: Row(
                      children:
                          [
                                'All',
                                'Active',
                                'Pending',
                                'Ready for Pickup',
                                'Completed',
                                'Rejected',
                              ]
                              .map(
                                (f) => Padding(
                                  padding: const EdgeInsets.only(right: 8),
                                  child: ChoiceChip(
                                    label: Text(f),
                                    selected: filter == f,
                                    onSelected: (_) =>
                                        setState(() => filter = f),
                                  ),
                                ),
                              )
                              .toList(),
                    ),
                  ),
                  const SizedBox(height: 18),
                  if (store.error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 16),
                      child: Text(
                        store.error!,
                        style: const TextStyle(color: Colors.red),
                      ),
                    ),
                  if (filtered.isEmpty)
                    panel(
                      const Column(
                        children: [
                          Icon(Icons.inbox_outlined, size: 42, color: green),
                          SizedBox(height: 14),
                          Text(
                            'Nothing in this queue yet.',
                            style: TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          SizedBox(height: 8),
                          Text('New print requests will appear here.'),
                        ],
                      ),
                    ),
                  ...filtered.map(
                    (order) => Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: OrderCard(
                        order: order,
                        onTap: () => Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) =>
                                OrderDetail(store: store, id: order.id),
                          ),
                        ),
                      ),
                    ),
                  ),
                  if (!store.admin) ...[
                    const SizedBox(height: 14),
                    const Row(
                      children: [
                        Icon(Icons.info_outline, size: 17, color: green),
                        SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Pay at the shop when you collect your prints.',
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.black54,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ],
              ],
            ),
          ),
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: [
          NavigationDestination(
            icon: const Icon(Icons.grid_view_rounded),
            label: store.admin ? 'Overview' : 'Home',
          ),
          NavigationDestination(
            icon: const Icon(Icons.receipt_long_outlined),
            label: store.admin ? 'Queue' : 'Orders',
          ),
          const NavigationDestination(
            icon: Icon(Icons.person_outline),
            label: 'Account',
          ),
        ],
      ),
    );
  }

  Widget _stat(String label, String value, IconData icon) => panel(
    Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: green),
        const SizedBox(height: 14),
        Text(
          value,
          style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w800),
        ),
        Text(
          label,
          style: const TextStyle(fontSize: 12, color: Colors.black54),
        ),
      ],
    ),
  );
  List<Widget> _account() => [
    panel(
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const CircleAvatar(
            radius: 30,
            backgroundColor: Color(0xFFEAF0DF),
            child: Icon(Icons.person, size: 30, color: green),
          ),
          const SizedBox(height: 18),
          Text(
            store.name,
            style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          Text(store.email),
          const SizedBox(height: 18),
          Chip(label: Text(store.admin ? 'Shop administrator' : 'Student')),
          const Divider(height: 32),
          const Text(
            'Campus Print Shop\nBSU Alangilan Campus\n\nPDF only · Maximum 5 MB\nPayment upon pickup',
            style: TextStyle(height: 1.7),
          ),
          const SizedBox(height: 20),
          const Text(
            'Sample pricing',
            style: TextStyle(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          const Text(
            'A4 / Letter: ₱2 black & white, ₱10 color per printed side. Legal adds ₱1 per side. Duplex saves paper; the per-side rate is unchanged. Confirm final pricing at the shop.',
            style: TextStyle(height: 1.6),
          ),
          if (store.demo) ...[
            const Divider(height: 32),
            OutlinedButton.icon(
              onPressed: () {
                store.demoLogin(!store.admin);
                setState(() {
                  tab = 0;
                  filter = 'All';
                });
              },
              icon: const Icon(Icons.swap_horiz),
              label: Text(
                store.admin
                    ? 'Switch to student demo'
                    : 'Switch to administrator demo',
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Demo orders reset when the app restarts.',
              style: TextStyle(fontSize: 12, color: Colors.black54),
            ),
          ],
        ],
      ),
    ),
  ];
  Future<void> _newOrder(BuildContext context) => Navigator.push(
    context,
    MaterialPageRoute(builder: (_) => NewOrderScreen(store: store)),
  );
}

class StatusBadge extends StatelessWidget {
  const StatusBadge(this.status, {super.key});
  final String status;
  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      'Ready for Pickup' || 'Completed' => green,
      'Rejected' => const Color(0xFFB04438),
      'Printing' => const Color(0xFF3B61A0),
      _ => const Color(0xFF946C20),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .09),
        borderRadius: BorderRadius.circular(9),
      ),
      child: Text(
        status,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          color: color,
        ),
      ),
    );
  }
}

class OrderCard extends StatelessWidget {
  const OrderCard({super.key, required this.order, required this.onTap});
  final PrintOrder order;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF0F3EA),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(
                    Icons.picture_as_pdf_outlined,
                    color: green,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        order.fileName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 15,
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        '${order.options.pages} pages · ${order.options.copies} copies · ${order.options.paper}',
                        style: const TextStyle(
                          color: Colors.black54,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right, color: Colors.black38),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                StatusBadge(order.status),
                const Spacer(),
                Text(
                  money(order.options.total),
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 17,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              '${order.queue == null ? 'Awaiting acceptance' : 'Queue #${order.queue.toString().padLeft(3, '0')}'}  ·  ${dateLabel(order.createdAt)}',
              style: const TextStyle(color: Colors.black45, fontSize: 11),
            ),
          ],
        ),
      ),
    ),
  );
}

class NewOrderScreen extends StatefulWidget {
  const NewOrderScreen({super.key, required this.store});
  final AppStore store;
  @override
  State<NewOrderScreen> createState() => _NewOrderScreenState();
}

class _NewOrderScreenState extends State<NewOrderScreen> {
  SelectedPdf? pdf;
  String paper = 'A4';
  int copies = 1;
  bool color = false, duplex = false, selecting = false;
  PrintOptions get options => PrintOptions(
    paper: paper,
    pages: pdf?.pages ?? 1,
    copies: copies,
    color: color,
    duplex: duplex,
  );
  Future<void> pick() async {
    setState(() => selecting = true);
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['pdf'],
        withData: true,
      );
      if (result == null) return;
      final file = result.files.single;
      final bytes = file.bytes;
      if (bytes == null || bytes.length < 5 || bytes.length > 5 * 1024 * 1024) {
        throw StateError('Choose a PDF up to 5 MB.');
      }
      if (String.fromCharCodes(bytes.take(5)) != '%PDF-') {
        throw StateError('This file is not a valid PDF.');
      }
      await pdfrxFlutterInitialize();
      final document = await PdfDocument.openData(bytes);
      try {
        if (document.pages.isEmpty || document.pages.length > 1000) {
          throw StateError('Choose a document with 1–1,000 pages.');
        }
        if (mounted) {
          setState(
            () => pdf = SelectedPdf(file.name, bytes, document.pages.length),
          );
        }
      } finally {
        await document.dispose();
      }
    } catch (e) {
      if (mounted) {
        showError(
          context,
          'Could not read PDF. Use an unlocked, valid PDF. $e',
        );
      }
    } finally {
      if (mounted) setState(() => selecting = false);
    }
  }

  Future<void> submit() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Confirm your print request'),
        content: Text(
          '${pdf!.name}\n\n${options.pages} pages × $copies copies\n$paper · ${color ? 'Color' : 'Black & white'}\n${duplex ? 'Double-sided' : 'Single-sided'} · ${options.sheets} sheets\n\nEstimated total: ${money(options.total)}\nPay at pickup. The shop will review your document.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Go back'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Submit request'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      await widget.store.submit(pdf!, options);
      if (mounted) {
        Navigator.pop(context);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Request submitted. Track it in your orders.'),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        showError(
          context,
          'Submission failed. Check your connection and retry. $e',
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.store,
    builder: (context, _) => PopScope(
      canPop: !widget.store.busy,
      child: Scaffold(
        appBar: AppBar(title: const Text('New print request')),
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 640),
            child: ListView(
              padding: const EdgeInsets.all(22),
              children: [
                eyebrow('01 / YOUR DOCUMENT'),
                const SizedBox(height: 12),
                panel(
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Icon(
                        Icons.upload_file_rounded,
                        color: green,
                        size: 44,
                      ),
                      const SizedBox(height: 16),
                      Text(
                        pdf?.name ?? 'Let’s get it on paper.',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontSize: 21,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        pdf == null
                            ? 'PDF documents only · Up to 5 MB'
                            : '${pdf!.pages} pages · ${(pdf!.bytes.length / 1024).ceil()} KB',
                        textAlign: TextAlign.center,
                        style: const TextStyle(color: Colors.black54),
                      ),
                      const SizedBox(height: 18),
                      OutlinedButton.icon(
                        onPressed: selecting || widget.store.busy ? null : pick,
                        icon: const Icon(Icons.attach_file),
                        label: Text(
                          selecting
                              ? 'Reading document…'
                              : pdf == null
                              ? 'Choose a PDF'
                              : 'Replace document',
                        ),
                      ),
                      if (pdf != null)
                        TextButton(
                          onPressed: () => Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) =>
                                  PdfScreen(bytes: pdf!.bytes, name: pdf!.name),
                            ),
                          ),
                          child: const Text('Preview document'),
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: 28),
                eyebrow('02 / MAKE IT YOURS'),
                const SizedBox(height: 12),
                panel(
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Paper size',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 12),
                      SegmentedButton<String>(
                        segments: ['A4', 'Letter', 'Legal']
                            .map((p) => ButtonSegment(value: p, label: Text(p)))
                            .toList(),
                        selected: {paper},
                        onSelectionChanged: widget.store.busy
                            ? null
                            : (s) => setState(() => paper = s.first),
                      ),
                      const SizedBox(height: 22),
                      Row(
                        children: [
                          const Expanded(
                            child: Text(
                              'Number of copies',
                              style: TextStyle(fontWeight: FontWeight.bold),
                            ),
                          ),
                          IconButton(
                            tooltip: 'Fewer copies',
                            onPressed: copies > 1 && !widget.store.busy
                                ? () => setState(() => copies--)
                                : null,
                            icon: const Icon(Icons.remove_circle_outline),
                          ),
                          Text(
                            '$copies',
                            style: const TextStyle(
                              fontSize: 20,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          IconButton(
                            tooltip: 'More copies',
                            onPressed: copies < 100 && !widget.store.busy
                                ? () => setState(() => copies++)
                                : null,
                            icon: const Icon(Icons.add_circle_outline),
                          ),
                        ],
                      ),
                      const Divider(height: 24),
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Print in color'),
                        subtitle: const Text('Off for black & white'),
                        value: color,
                        onChanged: widget.store.busy
                            ? null
                            : (v) => setState(() => color = v),
                      ),
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Double-sided'),
                        subtitle: const Text('Use both sides and save paper'),
                        value: duplex,
                        onChanged: widget.store.busy
                            ? null
                            : (v) => setState(() => duplex = v),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 28),
                eyebrow('03 / THE LITTLE DETAILS'),
                const SizedBox(height: 12),
                panel(
                  Column(
                    children: [
                      Row(
                        children: [
                          const Expanded(
                            child: Text(
                              'Estimated total',
                              style: TextStyle(fontSize: 16),
                            ),
                          ),
                          Text(
                            pdf == null ? '—' : money(options.total),
                            style: const TextStyle(
                              fontSize: 30,
                              fontWeight: FontWeight.w800,
                              color: green,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Text(
                        pdf == null
                            ? 'Choose a document to calculate your estimate.'
                            : '${options.pages} pages × $copies copies × ${money(options.rate)} per printed side\n${options.sheets} sheets · Payment upon pickup',
                        style: const TextStyle(
                          color: Colors.black54,
                          height: 1.6,
                        ),
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        'Sample shop rates. The administrator reviews the PDF before acceptance.',
                        style: TextStyle(fontSize: 12, color: Colors.black45),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 24),
                if (widget.store.busy) ...[
                  LinearProgressIndicator(
                    value: widget.store.progress > 0
                        ? widget.store.progress
                        : null,
                  ),
                  const SizedBox(height: 12),
                  const Text('Uploading and saving your request…'),
                  const SizedBox(height: 12),
                ],
                FilledButton.icon(
                  onPressed: pdf == null || widget.store.busy || selecting
                      ? null
                      : submit,
                  icon: const Icon(Icons.arrow_forward_rounded),
                  label: const Text('Review & submit'),
                ),
                const SizedBox(height: 24),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

class OrderDetail extends StatefulWidget {
  const OrderDetail({super.key, required this.store, required this.id});
  final AppStore store;
  final String id;
  @override
  State<OrderDetail> createState() => _OrderDetailState();
}

class _OrderDetailState extends State<OrderDetail> {
  bool loadingPdf = false;
  Future<void> preview(PrintOrder order) async {
    setState(() => loadingPdf = true);
    try {
      final bytes = await widget.store.documentBytes(order);
      if (mounted) {
        await Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => PdfScreen(bytes: bytes, name: order.fileName),
          ),
        );
      }
    } catch (e) {
      if (mounted) showError(context, e);
    } finally {
      if (mounted) setState(() => loadingPdf = false);
    }
  }

  Future<void> change(PrintOrder order, String status) async {
    String note = '';
    if (status == 'Rejected') {
      String reason = '';
      final result = await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Why reject this request?'),
          content: TextField(
            onChanged: (value) => reason = value,
            maxLength: 500,
            decoration: const InputDecoration(
              labelText: 'Reason shown to student',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () {
                if (reason.trim().isNotEmpty) {
                  Navigator.pop(context, reason.trim());
                }
              },
              child: const Text('Reject request'),
            ),
          ],
        ),
      );
      if (result == null) return;
      note = result;
    }
    try {
      await widget.store.updateStatus(order, status, note: note);
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.store,
    builder: (context, _) {
      final matches = widget.store.orders.where((o) => o.id == widget.id);
      if (matches.isEmpty) {
        return Scaffold(
          appBar: AppBar(),
          body: const Center(child: Text('Order unavailable.')),
        );
      }
      final order = matches.first;
      final index = statuses.indexOf(order.status);
      return Scaffold(
        appBar: AppBar(title: const Text('Print request')),
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 640),
            child: ListView(
              padding: const EdgeInsets.all(22),
              children: [
                panel(
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      StatusBadge(order.status),
                      const SizedBox(height: 22),
                      Text(
                        order.queue == null
                            ? 'We have your request.'
                            : 'Queue #${order.queue.toString().padLeft(3, '0')}',
                        style: const TextStyle(
                          fontSize: 30,
                          fontWeight: FontWeight.w800,
                          letterSpacing: -1,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        order.queueDay == null
                            ? 'The shop will review your document shortly.'
                            : 'Assigned ${order.queueDay} · Campus Print Shop',
                        style: const TextStyle(color: Colors.black54),
                      ),
                      if (order.status == 'Ready for Pickup') ...[
                        const SizedBox(height: 18),
                        const Text(
                          'Your prints are ready! Collect and pay at the shop.',
                          style: TextStyle(
                            color: green,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                      if (order.note.isNotEmpty) ...[
                        const SizedBox(height: 16),
                        Text('Shop note: ${order.note}'),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 24),
                const Text(
                  'From upload to pickup',
                  style: TextStyle(fontSize: 21, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 16),
                if (order.status != 'Rejected')
                  panel(
                    Column(
                      children: List.generate(
                        5,
                        (i) => Padding(
                          padding: const EdgeInsets.symmetric(vertical: 9),
                          child: Row(
                            children: [
                              Icon(
                                i <= index
                                    ? Icons.check_circle
                                    : Icons.radio_button_unchecked,
                                color: i <= index ? green : Colors.black26,
                              ),
                              const SizedBox(width: 14),
                              Text(
                                statuses[i],
                                style: TextStyle(
                                  fontWeight: i == index
                                      ? FontWeight.bold
                                      : FontWeight.normal,
                                  color: i <= index ? ink : Colors.black45,
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                const SizedBox(height: 24),
                panel(
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        order.fileName,
                        style: const TextStyle(
                          fontSize: 19,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '${order.studentName} · ${dateLabel(order.createdAt)}',
                        style: const TextStyle(
                          color: Colors.black54,
                          fontSize: 12,
                        ),
                      ),
                      const Divider(height: 30),
                      _line('Paper', order.options.paper),
                      _line(
                        'Pages / copies',
                        '${order.options.pages} / ${order.options.copies}',
                      ),
                      _line(
                        'Color',
                        order.options.color ? 'Full color' : 'Black & white',
                      ),
                      _line(
                        'Sides',
                        order.options.duplex ? 'Double-sided' : 'Single-sided',
                      ),
                      _line('Sheets', '${order.options.sheets}'),
                      const Divider(height: 24),
                      _line('Estimated total', money(order.options.total)),
                      const SizedBox(height: 10),
                      OutlinedButton.icon(
                        onPressed: loadingPdf ? null : () => preview(order),
                        icon: const Icon(Icons.picture_as_pdf_outlined),
                        label: Text(loadingPdf ? 'Loading PDF…' : 'View PDF'),
                      ),
                      const SizedBox(height: 8),
                      const Text(
                        'Pay at pickup. Bring your queue number.',
                        style: TextStyle(fontSize: 12, color: Colors.black54),
                      ),
                    ],
                  ),
                ),
                if (widget.store.admin) ...[
                  const SizedBox(height: 24),
                  ...statuses
                      .where((s) => canTransition(order.status, s))
                      .map(
                        (s) => Padding(
                          padding: const EdgeInsets.only(bottom: 10),
                          child: s == 'Rejected'
                              ? OutlinedButton(
                                  onPressed: widget.store.busy
                                      ? null
                                      : () => change(order, s),
                                  child: const Text('Reject request'),
                                )
                              : FilledButton(
                                  onPressed: widget.store.busy
                                      ? null
                                      : () => change(order, s),
                                  child: Text(
                                    s == 'Accepted'
                                        ? 'Accept & assign queue number'
                                        : 'Mark as $s',
                                  ),
                                ),
                        ),
                      ),
                ],
                const SizedBox(height: 30),
              ],
            ),
          ),
        ),
      );
    },
  );
  Widget _line(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 7),
    child: Row(
      children: [
        Expanded(
          child: Text(label, style: const TextStyle(color: Colors.black54)),
        ),
        Text(value, style: const TextStyle(fontWeight: FontWeight.w600)),
      ],
    ),
  );
}

class PdfScreen extends StatelessWidget {
  const PdfScreen({super.key, required this.bytes, required this.name});
  final Uint8List bytes;
  final String name;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(name)),
    body: PdfViewer.data(bytes, sourceName: name),
  );
}
