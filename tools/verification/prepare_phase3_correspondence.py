"""Freeze a bounded relationship development comparison, keeping synthetic checks separate from real evidence."""
import argparse
import hashlib
import json
from pathlib import Path


def prepare(snapshot_path, output):
    assert not output.exists(), 'Refusing overwrite'
    snapshot = json.loads(snapshot_path.read_text())
    assert snapshot['comparison'] == 'CORRESPONDENCE_ON_OFF' and not snapshot.get('heldback')
    ids = {'E01', 'E02', 'E03', 'E04', 'E05', 'E06', 'E07', 'N01', 'N12', 'R06'}
    real = [r for r in snapshot['development'] if r['review_id'] in ids]
    assert {r['review_id'] for r in real} == ids
    account = real[0]['source_message']['account_id']
    for row in real:
        assert row['source_message']['account_id'] == account
        relation = row['source_message']['relationship']
        cutoff = row['source_message']['history_before']
        from datetime import datetime
        for field in ['lastSentAt', 'lastReceivedAt']:
            if relation[field]:
                assert datetime.fromisoformat(relation[field]) < datetime.fromisoformat(cutoff)
        row['evaluation_kind'] = 'REVIEWED_REAL_DEVELOPMENT'
    controls = []
    scenarios = [
        ('PERSON', 'alex@example.test', 'Alex', 'Could you send me the venue address for Saturday dinner?', 'PERSONAL', True, False),
        ('SUPPORT', 'support@example.test', 'Support Desk', 'Automatic acknowledgement: support ticket #12345 was received', 'NOTIFICATION', False, False),
        ('PROMO', 'offers@example.test', 'Store Offers', 'Flash sale: take 25% off your next purchase today', 'MARKETING', False, False),
        ('SECURITY', 'security@example.test', 'Account Security', 'Your one-time sign-in verification code is 735291', 'SECURITY', False, True),
    ]
    for case, address, name, subject, purpose, human, security in scenarios:
        for sent in [0, 1, 5, 3000]:
            identity = f'CONTROL-{case}-{sent}'
            controls.append({'review_id': identity, 'split': 'DEVELOPMENT', 'evaluation_kind': 'SYNTHETIC_SENSITIVITY_ONLY',
                'source_message': {'account_id': account, 'provider_message_id': identity, 'evidence_source': 'SYNTHETIC_HEADERS',
                    'address': address, 'from_display_name': name, 'subject_header': subject,
                    'relationship': {'coverage': 'PARTIAL', 'sent': sent, 'received': 0, 'sentReplies': 0, 'receivedReplies': 0,
                                     'lastSentAt': '2026-10-01T12:00:00Z' if sent else '', 'lastReceivedAt': ''}},
                'expected': {'purpose': purpose, 'human_correspondence': human, 'security_access': security},
                'reference_provenance': 'Constructed unambiguous control; not a user-reviewed real message or evidence of real-world accuracy'})
    snapshot.update({'schema': 'phase3-correspondence-comparison-v1', 'development': real + controls, 'review_queue': [],
        'purpose_min_probability': 0.85, 'purpose_min_confidence': 0.85,
        'contract_source_sha256': hashlib.sha256(Path('src/main/java/com/tidymail/service/SemanticQualificationContract.java').read_bytes()).hexdigest(),
        'runner_source_sha256': hashlib.sha256(Path('tools/verification/Phase3JevPilot.java').read_bytes()).hexdigest(),
        'snapshot_sha256': hashlib.sha256(snapshot_path.read_bytes()).hexdigest(),
        'reference_policy': 'Real references unchanged. Unknown properties unscored. Synthetic controls scored separately. No held-back reuse. No threshold tuning after results.',
        'adoption_policy': 'Development evidence only. Do not enable production from these results. No count-based gate or rule learning.',
        'limitations': ['One account; previously exposed development examples', 'Retained pre-message evidence, not a historical snapshot of labels/availability',
                       'Synthetic counts are sensitivity checks, not real correspondence histories', 'No reviewed real accidental-send or exactly-one-send contact in this pack']})
    output.write_text(json.dumps(snapshot, indent=2) + '\n')
    print(f'Frozen: {len(real)} reviewed real examples + {len(controls)} synthetic sensitivity controls; two matched arms.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    prepare(args.snapshot, args.output)
