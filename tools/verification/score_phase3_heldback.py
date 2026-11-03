"""Score one frozen held-back run without modifying questions, thresholds or references."""
import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import json
from pathlib import Path


def load(path):
    return json.loads(path.read_text(), parse_float=Decimal)


def score(root):
    policy_path = root.parent / 'phase3-heldback-policy-freeze.json'
    policy, freeze, ledger = load(policy_path), load(root / 'freeze.json'), load(root / 'ledger.json')
    assert ledger['experiment_scope'] == freeze['experiment_scope'] == 'HELD_BACK_ONCE'
    assert ledger['status'] == 'DEVELOPMENT_RUN_COMPLETE'  # Historical runner completion enum, not evaluation split.
    assert hashlib.sha256(policy_path.read_bytes()).hexdigest() == freeze['policy_sha256']
    assert freeze['contract_source_hash'] == policy['contract_source_sha256']
    assert freeze['variants'] == ['with_gmail_categories']
    for key in ['purpose_min_confidence', 'purpose_min_probability', 'signal_yes', 'signal_no']:
        assert freeze[key] == policy[key]
    rows = [row for p in sorted(root.glob('result-*.json')) for row in load(p)['judgments']]
    assert len(rows) == len({r['review_id'] for r in rows}) == 16
    assert {r['review_id'] for r in rows} == set(policy['review_ids'])
    for path in root.glob('request-*.json'):
        for state in load(path)['state'].values():
            assert set(state) <= {'address', 'sender_name', 'subject', 'is_reply', 'has_unsubscribe', 'gmail_categories'}
    properties = {'purpose': Counter(), 'security_access': Counter(), 'human_correspondence': Counter(),
                  'social_platform': Counter(), 'order': Counter(), 'shipping': Counter(), 'promotion': Counter()}
    errors, details = [], []
    for row in rows:
        expected, judgment = row['expected'], row['judgment']
        choice = judgment['purpose']
        resolved = (choice['selected'] != 'UNCERTAIN' and choice['confidence'] >= policy['purpose_min_confidence']
                    and choice['probabilities'][choice['selected']] >= policy['purpose_min_probability'])
        predictions = {'purpose': choice['selected'] if resolved else None}
        for field, signal in [('security_access', 'SECURITY'), ('human_correspondence', 'HUMAN_CORRESPONDENCE'),
                              ('social_platform', 'SOCIAL_PLATFORM'), ('order', 'ORDER'), ('shipping', 'SHIPPING'),
                              ('promotion', 'PROMOTION')]:
            probability = judgment['signals'][signal]
            predictions[field] = True if probability >= policy['signal_yes'] else False if probability <= policy['signal_no'] else None
        for field, selected in predictions.items():
            reference = expected.get(field)
            counts = properties[field]
            if reference is None:
                counts['unknown_reference_unscored'] += 1
                continue
            counts['reviewed'] += 1
            if selected is None:
                counts['unresolved'] += 1
            elif selected == reference:
                counts['resolved_correct'] += 1
            else:
                counts['resolved_incorrect'] += 1
                errors.append({'review_id': row['review_id'], 'property': field, 'expected': reference, 'selected': selected})
        details.append({'review_id': row['review_id'], 'expected': expected, 'resolved_predictions': predictions,
                        'purpose_selected': choice['selected'], 'purpose_confidence': str(choice['confidence']),
                        'purpose_selected_probability': str(choice['probabilities'][choice['selected']]),
                        'signal_probabilities': {k: str(v) for k, v in judgment['signals'].items()}})
    total = sum((a['reported_usd'] for a in ledger['attempts']), Decimal(0))
    assert total == ledger['reported_usd']
    assert all(a['state'] == 'REPORTED' and a['validated'] for a in ledger['attempts'])
    return {'status': 'FROZEN_HELDBACK_GATE_FAILED' if errors else 'FROZEN_HELDBACK_GATE_PASSED_BOUNDED_SCOPE',
            'messages': len(rows), 'requests': len(ledger['attempts']), 'properties': {k: dict(v) for k, v in properties.items()},
            'resolved_errors': errors, 'reported_usd': str(total),
            'cumulative_experiment_reported_usd': str(total + ledger['prior_experiment_reported_usd']),
            'details': details,
            'limits': ['One small held-back semantic sample, not mailbox-wide accuracy',
                       'Unknown references and uncertain predictions are unscored or unresolved, never assumed correct',
                       'Template screening is conservative; earlier capability identifiers were unavailable',
                       'H03 disposal preference is conditional; no expiry, calendar finality, importance or cleanup accuracy claim',
                       'No automatic production adoption, fallback, body/snippet disclosure or mailbox action']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    report = score(args.directory)
    (args.directory / 'heldback-summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'details'}, sort_keys=True))
