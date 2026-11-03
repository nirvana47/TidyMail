"""Build a private review pack from existing local files. No network or model calls.

Candidate buckets and template fingerprints are screening aids, never reference labels.
Previously exposed messages, threads and exact normalized templates cannot enter the
prospective held-back queue. Human template-family review is still required.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import quote


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def identity(row):
    return str(row['account_id']), row['provider_message_id']


def template(row):
    # Conservative cross-account/address template screen; not a semantic family claim.
    return row.get('address', '').strip().lower(), re.sub(r'\d{6,}', '{number}', row.get('subject_header', '').strip().lower())


def thread(row):
    value = row.get('provider_thread_id')
    return (str(row['account_id']), value) if value else None


def load(path):
    return json.loads(path.read_text())


def prepare(root, output):
    source_paths = [root / '2026-11-01/phase2-private-pattern-review.json',
                    root / '2026-11-01/phase2-private-pattern-target-review.json',
                    root / '2026-11-02/phase2-private-exception-review.json']
    reviewed = []
    for path in source_paths:
        data = load(path)
        if data.get('status') != 'USER_REVIEWED':
            raise ValueError('Source reference is not user reviewed')
        reviewed.extend(data['rows'])
    if len({identity(r) for r in reviewed}) != len(reviewed):
        raise ValueError('Duplicate source references require explicit reconciliation')
    neighbors = root / '2026-11-01/phase2-private-pattern-neighbors.jsonl'
    exposed = reviewed + [json.loads(line) for line in neighbors.read_text().splitlines() if line.strip()]
    ids = {identity(r) for r in exposed}
    templates = {template(r) for r in exposed}
    threads = {thread(r) for r in exposed if thread(r)}
    development = []
    for row in reviewed:
        expected = {'purpose': row.get('reviewed_category'), 'security_access': row.get('security_access'),
                    'promotion': None, 'order': None, 'shipping': None, 'human_correspondence': None,
                    'importance': row.get('importance'), 'past_event': None, 'disposal_suitability': None}
        # These exact notes were explicitly supplied by the user, not inferred from purpose.
        if row['review_id'] in {'R03', 'R04', 'R05'}:
            expected['importance'] = 'UNIMPORTANT'
        development.append({'review_id': row['review_id'], 'split': 'DEVELOPMENT', 'source_message': row,
                            'template_screen_hash': digest(template(row)), 'template_family_review': 'PENDING',
                            'expected': expected, 'reference_provenance': 'EXISTING_USER_REVIEW',
                            'evidence_tier': 'HEADERS_ONLY', 'previously_exposed': True})
    candidates = load(root / '2026-11-02/phase3-private-index-candidates.json')
    if candidates.get('status') != 'UNREVIEWED_LOCAL_INDEX_CANDIDATES':
        raise ValueError('Unexpected candidate source')
    available = []
    rejected = Counter()
    for row in candidates['rows']:
        reason = ('exposed_message' if identity(row) in ids else
                  'exposed_thread' if thread(row) and thread(row) in threads else
                  'exposed_template' if template(row) in templates else None)
        if reason:
            rejected[reason] += 1
        else:
            available.append(row)
    # Round-robin strata. Unknown or missing strata remain gaps; do not fabricate cases.
    buckets = sorted({r['bucket'] for r in available})
    selected = []
    selected_ids, selected_templates, selected_threads = set(), set(), set()
    for position in range(12):
        for bucket in buckets:
            choices = [r for r in available if r['bucket'] == bucket]
            if position >= len(choices) or len(selected) >= 17:
                continue
            row = choices[position]
            if (identity(row) in selected_ids or template(row) in selected_templates
                    or (thread(row) and thread(row) in selected_threads)):
                continue
            selected.append(row)
            selected_ids.add(identity(row)); selected_templates.add(template(row))
            if thread(row):
                selected_threads.add(thread(row))
    # Keep the scarce access/security stratum available for prospective held-back review.
    development_index = next((i for i, row in enumerate(selected) if row['bucket'] == 'routine_candidate'), 0)
    if selected:
        selected.insert(0, selected.pop(development_index))
    queue = []
    for i, row in enumerate(selected, 1):
        queue.append({'review_id': f'N{i:02}', 'proposed_split': 'DEVELOPMENT' if i == 1 else 'HELD_BACK_CANDIDATE',
                      'status': 'AWAITING_USER_LABELS_AND_TEMPLATE_FAMILY_REVIEW', 'source_message': row,
                      'template_screen_hash': digest(template(row)), 'template_family': None,
                      'expected': {key: None for key in development[0]['expected']},
                      'bucket_is_sampling_hint_only': row['bucket']})
    # The development-touched corpus is never relabeled as held back.
    assert not selected_ids.intersection(ids)
    assert not selected_templates.intersection(templates)
    assert not selected_threads.intersection(threads)
    summary = {'reviewed_development': len(development),
               'known_purpose': sum(r['expected']['purpose'] is not None for r in development),
               'known_access': sum(r['expected']['security_access'] is not None for r in development),
               'fresh_review_candidates': len(queue), 'qualified_held_back': 0,
               'candidate_bucket_counts': dict(Counter(r['bucket'] for r in selected)),
               'excluded_candidates': dict(rejected), 'screened_previously_exposed_unique_messages': len(ids),
               'minimum_additional_messages_for_40': max(0, 40 - len(development)),
               'unfilled_candidate_slots': max(0, 17 - len(queue))}
    manifest = {'schema': 'phase3-reference-v1', 'status': 'PREPARATION_ONLY_NOT_BENCHMARK_READY',
                'source_file_hashes': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                                      for p in source_paths + [neighbors, root / '2026-11-02/phase3-private-index-candidates.json']},
                'summary': summary, 'development': development, 'review_queue': queue,
                'limitations': ['Exact normalized templates do not establish independent semantic template families',
                                'Earlier capability reports omitted message identifiers; prior-exposure screening is incomplete',
                                'No threshold, composition or escalation policy is frozen',
                                'Unknown reference properties are unscored, never negative labels',
                                'Header candidates do not establish MIME, inline content or calendar end state',
                                'Older/excluded development exceptions remain boundary cases, not cleanup candidates']}
    output.mkdir(parents=True, exist_ok=True)
    destination = output / 'phase3-private-reference-manifest.json'
    if destination.exists():
        raise ValueError('Refusing to overwrite a reference manifest')
    destination.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    lines = ['# Phase 3 private candidate review', '',
             'These are unreviewed headers from the existing local index. No mail was retrieved or changed. '
             'Sampling hints are not labels. N01 is a proposed development addition; N02 onward are only '
             'prospective held-back cases pending template-family review and a frozen evaluation policy.', '',
             'For each, give primary purpose, access/security=yes/no/unknown, and human correspondence=yes/no/unknown. '
             'Optional independent signals: promotion, order, shipping. Unknown is valid. '
             'Importance is optional and separate. Do not infer event completion from these headers. '
             'Flag any example previously used in capability checks, or any template similar to an earlier review.', '',
             'Categories: Personal, Marketing, Newsletter, Receipt, Security, Notification, Other, Unknown. '
             'For example: `N01=Notification; access=no; human=no; shipping=yes`. '
             'Only confirm signals supported by what you know or can inspect in the linked email.', '',
             '| ID | Sender | Subject | Email |', '|---|---|---|---|']
    def safe(value):
        return str(value or '').replace('|', '\\|').replace('\n', ' ').replace('\r', ' ').replace('<', '&lt;').replace('>', '&gt;')
    for entry in queue:
        row = entry['source_message']
        url = 'https://mail.google.com/mail/u/' + quote(row['account_email'], safe='') + '/#all/' + quote(row['provider_message_id'], safe='')
        lines.append(f"| {entry['review_id']} | {safe(row['address'])} | {safe(row['subject_header'])} | [Open]({url}) |")
    lines += ['', 'No existing answers need to be repeated. The four unspecified Phase 2 primary categories remain unknown. '
              'Held-back qualification also requires checking broader template families; exact-subject screening alone is insufficient.', '']
    (output / 'V1-PHASE-3-PRIVATE-CANDIDATE-REVIEW.md').write_text('\n'.join(lines))
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verification-root', type=Path, required=True)
    parser.add_argument('--output-directory', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.verification_root, args.output_directory), sort_keys=True))
