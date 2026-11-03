"""Audit matched history on/off requests; score reviewed real messages separately from synthetic sensitivity controls."""
import argparse
from collections import Counter
from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path


def load(path):
    return json.loads(path.read_text(), parse_float=Decimal)


def compare(root):
    freeze, ledger = load(root / 'freeze.json'), load(root / 'ledger.json')
    snapshot = load(root / 'evidence-snapshot.json')
    variants = ['without_correspondence', 'with_correspondence']
    assert freeze['variants'] == variants and ledger['status'] == 'DEVELOPMENT_RUN_COMPLETE'
    real_ids = {r['reviewId'] for r in snapshot if not r['suppliedHeaders']}
    expected_ids = set(freeze['development_review_ids'])
    assert len(expected_ids) == len(freeze['development_review_ids'])
    rows, requests = {v: {} for v in variants}, {v: {} for v in variants}
    charges = {v: Decimal(0) for v in variants}
    models = set()
    for attempt in ledger['attempts']:
        assert attempt['state'] == 'REPORTED' and attempt['validated']
        seq, variant = attempt['sequence'], attempt['variant']
        request = load(root / f'request-{seq:02d}.json')
        response = load(root / f'response-{seq:02d}.json')
        result = load(root / f'result-{seq:02d}.json')
        assert result['variant'] == variant and result['request_hash'] == attempt['request_hash']
        assert response['provider'] == 'TypeSafe'
        assert response['usage']['cost'] == attempt['reported_usd']
        models.add(response['model'])
        ids = tuple(attempt['review_ids'])
        assert ids not in requests[variant]
        requests[variant][ids] = request
        for row in result['judgments']:
            assert row['review_id'] not in rows[variant]
            rows[variant][row['review_id']] = row
        charges[variant] += attempt['reported_usd']
    assert len(models) == 1 and sum(charges.values()) == ledger['reported_usd']
    assert set(requests[variants[0]]) == set(requests[variants[1]])
    counts = Counter()
    for ids, without in requests[variants[0]].items():
        with_history = requests[variants[1]][ids]
        stripped = deepcopy(with_history)
        for state in stripped['state'].values():
            history = state.pop('relationship')
            assert set(history) == {'coverage', 'sent', 'received', 'sentReplies', 'receivedReplies', 'lastSentAt', 'lastReceivedAt'}
            counts[str(history['sent'])] += 1
            assert set(state) <= {'address', 'sender_name', 'subject', 'is_reply', 'has_unsubscribe', 'gmail_categories'}
        assert without == stripped, 'Inputs other than history changed between arms'
    for variant in variants:
        assert set(rows[variant]) == expected_ids

    def resolved(row):
        p = row['judgment']['purpose']
        return p['selected'] != 'UNCERTAIN' and p['confidence'] >= freeze['purpose_min_confidence'] and p['probabilities'][p['selected']] >= freeze['purpose_min_probability']

    def score(data, ids):
        known = [data[i] for i in ids if data[i]['expected'].get('purpose') is not None]
        accepted = [r for r in known if resolved(r)]
        result = {'examples': len(ids), 'known_purposes': len(known), 'unknown_purposes_unscored': len(ids) - len(known),
            'raw_purpose_correct': sum(r['judgment']['purpose']['selected'] == r['expected']['purpose'] for r in known),
            'resolved_correct': sum(r['judgment']['purpose']['selected'] == r['expected']['purpose'] for r in accepted),
            'resolved_errors': [r['review_id'] for r in accepted if r['judgment']['purpose']['selected'] != r['expected']['purpose']],
            'unresolved': len(known) - len(accepted)}
        for field, signal in [('security_access', 'SECURITY'), ('human_correspondence', 'HUMAN_CORRESPONDENCE')]:
            counts = Counter(correct=0, incorrect=0, unresolved=0)
            for identity in ids:
                r = data[identity]
                if r['expected'].get(field) is None:
                    continue
                p = r['judgment']['signals'][signal]
                value = True if p >= freeze['signal_yes'] else False if p <= freeze['signal_no'] else None
                counts['unresolved' if value is None else 'correct' if value == r['expected'][field] else 'incorrect'] += 1
            result[field] = dict(counts)
        return result

    arms = {v: {'reviewed_real': score(data, real_ids), 'synthetic_controls': score(data, expected_ids - real_ids),
                'reported_usd': str(charges[v])} for v, data in rows.items()}
    changes, controls = [], []
    for identity in freeze['development_review_ids']:
        a, b = [rows[v][identity] for v in variants]
        assert a['expected'] == b['expected']
        pa, pb = a['judgment']['purpose'], b['judgment']['purpose']
        item = {'review_id': identity, 'expected_purpose': a['expected'].get('purpose'),
            'without_purpose': pa['selected'], 'with_purpose': pb['selected'],
            'without_resolved': resolved(a), 'with_resolved': resolved(b),
            'without_selected_probability': str(pa['probabilities'][pa['selected']]),
            'with_selected_probability': str(pb['probabilities'][pb['selected']]),
            'without_human': str(a['judgment']['signals']['HUMAN_CORRESPONDENCE']),
            'with_human': str(b['judgment']['signals']['HUMAN_CORRESPONDENCE'])}
        if pa['selected'] != pb['selected'] or resolved(a) != resolved(b):
            changes.append(item)
        if identity not in real_ids:
            controls.append(item)
    return {'status': 'MATCHED_CORRESPONDENCE_DEVELOPMENT_COMPARISON_ONLY', 'matched_requests_verified': True,
        'messages_per_arm': len(expected_ids), 'request_count': len(ledger['attempts']), 'model': next(iter(models)),
        'arms': arms, 'purpose_or_resolution_changes': changes, 'synthetic_control_details': controls,
        'sent_count_distribution': dict(counts), 'reported_usd': str(ledger['reported_usd']),
        'limitations': ['One paired run; no claim of reproducibility or generalization to another account',
                       'Real messages are previously exposed development data; this is not held-back validation',
                       'Future exchanges excluded; retained current labels/availability remain retrospective',
                       'No real reviewed accidental-send or exactly-one-send example',
                       'Counts do not establish intent, human identity, importance, safety or cleanup permission',
                       'No production adoption or rule learning from this experiment']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    result = compare(args.directory)
    (args.directory / 'comparison-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, sort_keys=True))
