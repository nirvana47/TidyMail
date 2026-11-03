"""Validate and score the frozen on/off Gmail-category development comparison."""
import argparse
from collections import Counter
from decimal import Decimal
import json
from pathlib import Path


def load(path):
    return json.loads(path.read_text(), parse_float=Decimal)


def compare(root):
    freeze, ledger = load(root / 'freeze.json'), load(root / 'ledger.json')
    assert ledger['status'] == 'DEVELOPMENT_RUN_COMPLETE'
    variants = ['without_gmail_categories', 'with_gmail_categories']
    assert freeze['variants'] == variants
    expected_ids = set(freeze['development_review_ids'])
    rows = {v: {} for v in variants}
    requests = {v: {} for v in variants}
    response_models, response_providers = set(), set()
    charges = {v: Decimal(0) for v in variants}
    for attempt in ledger['attempts']:
        assert attempt['state'] == 'REPORTED' and attempt['validated']
        seq, variant = attempt['sequence'], attempt['variant']
        request = load(root / f'request-{seq:02d}.json')
        result = load(root / f'result-{seq:02d}.json')
        response = load(root / f'response-{seq:02d}.json')
        assert result['variant'] == variant and result['request_hash'] == attempt['request_hash']
        ids = tuple(attempt['review_ids'])
        assert ids not in requests[variant]
        requests[variant][ids] = request
        for row in result['judgments']:
            assert row['review_id'] not in rows[variant]
            rows[variant][row['review_id']] = row
        charges[variant] += attempt['reported_usd']
        response_models.add(response['model']); response_providers.add(response['provider'])
    assert sum(charges.values()) == ledger['reported_usd']
    assert response_providers == {'TypeSafe'}
    assert len(response_models) == 1
    for variant in variants:
        assert set(rows[variant]) == expected_ids
    assert set(requests[variants[0]]) == set(requests[variants[1]])
    categories = Counter()
    allowed_categories = {'CATEGORY_PERSONAL', 'CATEGORY_SOCIAL', 'CATEGORY_UPDATES', 'CATEGORY_PROMOTIONS', 'CATEGORY_FORUMS'}
    for ids, without in requests[variants[0]].items():
        with_categories = requests[variants[1]][ids]
        assert without['questions'] == with_categories['questions']
        stripped = json.loads(json.dumps(with_categories))
        for state in stripped['state'].values():
            for label in state.pop('gmail_categories', []):
                assert label in allowed_categories
                categories[label] += 1
        assert without == stripped, 'Non-category evidence changed between arms'
        for state in without['state'].values():
            assert set(state) <= {'address', 'sender_name', 'subject', 'is_reply', 'has_unsubscribe'}
    def resolved(row):
        purpose = row['judgment']['purpose']
        return (purpose['selected'] != 'UNCERTAIN' and purpose['confidence'] >= freeze['purpose_min_confidence']
                and purpose['probabilities'][purpose['selected']] >= freeze['purpose_min_probability'])
    def signal_score(data, field, signal):
        refs = [r for r in data.values() if r['expected'].get(field) is not None]
        counts = Counter({'reviewed': len(refs), 'reference_positive': sum(r['expected'][field] for r in refs),
                          'correct': 0, 'incorrect': 0, 'unresolved': 0})
        for row in refs:
            p = row['judgment']['signals'][signal]
            selected = True if p >= freeze['signal_yes'] else False if p <= freeze['signal_no'] else None
            counts['unresolved' if selected is None else 'correct' if selected == row['expected'][field] else 'incorrect'] += 1
        return dict(counts)
    summaries = {}
    for variant, data in rows.items():
        known = [r for r in data.values() if r['expected']['purpose'] is not None]
        accepted = [r for r in known if resolved(r)]
        wrong = [r['review_id'] for r in accepted if r['judgment']['purpose']['selected'] != r['expected']['purpose']]
        summaries[variant] = {'reviewed_purposes': len(known), 'unknown_purposes_unscored': len(data) - len(known),
                              'raw_purpose_correct': sum(r['judgment']['purpose']['selected'] == r['expected']['purpose'] for r in known),
                              'resolved': len(accepted), 'resolved_correct': len(accepted) - len(wrong),
                              'resolved_errors': wrong, 'unresolved': len(known) - len(accepted),
                              'access': signal_score(data, 'security_access', 'SECURITY'),
                              'human_correspondence': signal_score(data, 'human_correspondence', 'HUMAN_CORRESPONDENCE'),
                              'reported_usd': str(charges[variant])}
    changes = []
    for identity in freeze['development_review_ids']:
        a, b = rows[variants[0]][identity], rows[variants[1]][identity]
        assert a['expected'] == b['expected']
        ca, cb = a['judgment']['purpose'], b['judgment']['purpose']
        if ca['selected'] != cb['selected'] or resolved(a) != resolved(b):
            changes.append({'review_id': identity, 'expected': a['expected']['purpose'],
                            'without_selected': ca['selected'], 'with_selected': cb['selected'],
                            'without_resolved': resolved(a), 'with_resolved': resolved(b)})
    return {'status': 'MATCHED_DEVELOPMENT_COMPARISON_ONLY', 'messages_per_arm': len(expected_ids),
            'request_count': len(ledger['attempts']), 'arms': summaries, 'purpose_selection_or_resolution_changes': changes,
            'gmail_category_counts': dict(categories), 'reported_usd': str(ledger['reported_usd']),
            'cumulative_experiment_reported_usd': str(ledger['reported_usd'] + ledger['prior_experiment_reported_usd']),
            'matched_requests_verified': True, 'model': next(iter(response_models)),
            'limitations': ['Single paired run, not a stochastic reproducibility study',
                            'Social-v2 taxonomy in both arms; no gain attributed against the older taxonomy',
                            'No held-back examples or richer-content passes',
                            'Gmail categories are evidence, never reference labels or disposal permission',
                            'No observed Forums examples; no per-category generalization claim']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    result = compare(args.directory)
    (args.directory / 'comparison-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, sort_keys=True))
