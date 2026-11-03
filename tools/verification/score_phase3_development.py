"""Score a frozen development run only, using its pre-dispatch thresholds and known references."""
import argparse
from collections import Counter
from decimal import Decimal
import json
from pathlib import Path


def load(path):
    return json.loads(path.read_text(), parse_float=Decimal)


def score(directory):
    freeze, ledger = load(directory / 'freeze.json'), load(directory / 'ledger.json')
    if ledger['status'] != 'DEVELOPMENT_RUN_COMPLETE':
        raise ValueError('Run is incomplete; preserve unresolved liabilities')
    rows = [r for p in sorted(directory.glob('result-*.json')) for r in load(p)['judgments']]
    ids = [r['review_id'] for r in rows]
    if len(ids) != len(set(ids)) or set(ids) != set(freeze['development_review_ids']):
        raise ValueError('Results do not match frozen development IDs')
    for path in directory.glob('request-*.json'):
        for state in load(path)['state'].values():
            if not {'address', 'sender_name', 'subject'}.issubset(state) or not set(state).issubset({'address', 'sender_name', 'subject', 'is_reply', 'has_unsubscribe', 'facts'}):
                raise ValueError('Unexpected disclosure field')
    def resolved(row):
        choice = row['judgment']['purpose']
        return (choice['selected'] != 'UNCERTAIN' and choice['confidence'] >= freeze['purpose_min_confidence']
                and choice['probabilities'][choice['selected']] >= freeze['purpose_min_probability'])
    known = [r for r in rows if r['expected']['purpose'] is not None]
    accepted = [r for r in known if resolved(r)]
    wrong = [r['review_id'] for r in accepted if r['judgment']['purpose']['selected'] != r['expected']['purpose']]
    def signal(field, signal):
        refs = [r for r in rows if r['expected'].get(field) is not None]
        counts = Counter({'reviewed': len(refs), 'reference_positive': sum(r['expected'][field] for r in refs),
                          'correct': 0, 'incorrect': 0, 'unresolved': 0})
        for row in refs:
            probability = row['judgment']['signals'][signal]
            value = True if probability >= freeze['signal_yes'] else False if probability <= freeze['signal_no'] else None
            counts['unresolved' if value is None else 'correct' if value == row['expected'][field] else 'incorrect'] += 1
        return dict(counts)
    attempts = ledger['attempts']
    total = sum((a['reported_usd'] for a in attempts), Decimal(0))
    assert total == ledger['reported_usd']
    assert all(a['state'] == 'REPORTED' and a.get('validated') for a in attempts)
    return {'status': 'DEVELOPMENT_ONLY_ADOPTION_GATE_FAILED' if wrong else 'DEVELOPMENT_ONLY_NOT_HELD_BACK_QUALIFIED',
            'messages': len(rows), 'requests': len(attempts), 'reported_usd': str(total),
            'purpose': {'reviewed': len(known), 'unknown_reference_unscored': len(rows) - len(known),
                        'raw_argmax_correct': sum(r['judgment']['purpose']['selected'] == r['expected']['purpose'] for r in known),
                        'resolved': len(accepted), 'resolved_correct': len(accepted) - len(wrong),
                        'resolved_error_ids': wrong, 'unresolved': len(known) - len(accepted)},
            'security_access': signal('security_access', 'SECURITY'),
            'human_correspondence': signal('human_correspondence', 'HUMAN_CORRESPONDENCE'),
            'social_platform': signal('social_platform', 'SOCIAL_PLATFORM'),
            'request_bytes': sum(a['request_bytes'] for a in attempts),
            'response_bytes': sum(a['response_bytes'] for a in attempts),
            'total_request_ms': sum(a['request_ms'] for a in attempts),
            'unknown_charge_attempts': 0, 'held_back_run_performed': False,
            'limitations': ['Development references are not an independent accuracy benchmark',
                            'Engineering-normalized purposes retain separate provenance',
                            'Promotion/order/shipping signals are not scored by this report',
                            'No matched baseline, snippet/body comparison, pass 2 or context-on/off experiment performed',
                            'No grouping, importance, calendar-finality or disposal decision is evaluated by this run']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    report = score(args.directory)
    (args.directory / 'score-summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, sort_keys=True))
