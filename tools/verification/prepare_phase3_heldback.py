"""Prepare a private held-back review pack; never fetch mail, infer labels or call a model."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import quote


def identity(row):
    return str(row['account_id']), row['provider_message_id']


def template(row):
    return row['address'].lower(), re.sub(r'\d{6,}', '{number}', row['subject_header'].strip().lower())


def prepare(base):
    manifest = json.loads((base / 'phase3-private-reference-manifest.json').read_text())
    candidates = json.loads((base / 'phase3-private-heldback-candidates.json').read_text())['rows']
    # Subject-level calendar skeletons overlap the exposed invitation family. Quarantine
    # the reply variant too: incomplete headers cannot establish family independence.
    quarantine = {'N04': 'Reply to an exposed invitation family; independence not established',
                  'N09': 'Updated-invitation skeleton overlaps E01/E02',
                  'N12': 'Amazon dropoff-confirmation template overlaps development N01',
                  'N14': 'Invitation skeleton overlaps E03'}
    retained = [r for r in manifest['review_queue'] if r['review_id'] not in quarantine
                and r['split_disposition'] != 'DEVELOPMENT']
    assert len(retained) == 12
    exposed = [r['source_message'] for r in manifest['development'] + manifest['review_queue']]
    neighbor_path = base.parent / '2026-11-01/phase2-private-pattern-neighbors.jsonl'
    exposed += [json.loads(line) for line in neighbor_path.read_text().splitlines() if line.strip()]
    used_ids = {identity(r) for r in exposed}
    used_templates = {template(r) for r in exposed}
    used_threads = {(str(r['account_id']), r['provider_thread_id']) for r in exposed if r.get('provider_thread_id')}
    selectors = [
        ('H01', lambda r: r['bucket'] == 'social_access_hint'),
        ('H02', lambda r: r['address'].lower() == 'noreply@redditmail.com'),
        ('H03', lambda r: r['address'].lower() == 'noreply@notify.cloudflare.com'),
        ('H04', lambda r: r['address'].lower() == 'noreply@accessoticketing.com'),
    ]
    fresh = []
    for review_id, condition in selectors:
        choices = [r for r in candidates if condition(r) and identity(r) not in used_ids
                   and template(r) not in used_templates
                   and (str(r['account_id']), r.get('provider_thread_id')) not in used_threads]
        if not choices:
            raise ValueError('Missing independently screened candidate for ' + review_id)
        row = choices[0]
        fresh.append({'review_id': review_id, 'source_message': row,
                      'status': 'AWAITING_USER_REFERENCE',
                      'expected': {'purpose': None, 'security_access': None, 'human_correspondence': None,
                                   'social_platform': None, 'order': None, 'shipping': None, 'promotion': None,
                                   'importance': None, 'past_event': None},
                      'sampling_hint_not_label': row['bucket']})
        used_ids.add(identity(row)); used_templates.add(template(row))
        if row.get('provider_thread_id'):
            used_threads.add((str(row['account_id']), row['provider_thread_id']))
    destination = base / 'phase3-private-heldback-pack.json'
    if destination.exists():
        raise ValueError('Refusing to overwrite held-back pack')
    pack = {'status': 'TWELVE_RETAINED_PLUS_FOUR_UNREVIEWED_NOT_QUALIFIED',
            'retained_user_reviewed': retained, 'fresh_review_queue': fresh,
            'quarantined': quarantine,
            'limitations': ['Broad family quarantine is conservative screening, not a parsed calendar claim',
                            'Earlier identifier-free capability samples prevent complete exposure proof',
                            'No held-back predictions have been requested; unknown references remain unscored',
                            'Four candidates do not guarantee four usable references or positive-security coverage']}
    destination.write_text(json.dumps(pack, indent=2, ensure_ascii=False) + '\n')
    lines = ['# Four held-back replacement reviews', '',
             'Your earlier answers are preserved. Four candidates replace template-related examples '
             'quarantined from the final test. These come from the existing local index; no Gmail or model request was made for them.', '',
             'For each, give primary purpose, access/security=yes/no/unknown and human correspondence=yes/no/unknown. '
             'Social / community covers ordinary platform/community content; purchases, promotions and access messages can have their own purpose. '
             'Optional: social-platform origin=yes/no/unknown and order/shipping signals. Unknown is valid. '
             'Flag an example if it resembles one already reviewed. No need to repeat any previous answer.', '',
             '| ID | Sender | Subject | Email |', '|---|---|---|---|']
    def safe(value):
        return str(value).replace('|', '\\|').replace('\n', ' ').replace('<', '&lt;').replace('>', '&gt;')
    for entry in fresh:
        row = entry['source_message']
        url = 'https://mail.google.com/mail/u/' + quote(row['account_email'], safe='') + '/#all/' + quote(row['provider_message_id'], safe='')
        lines.append(f"| {entry['review_id']} | {safe(row['address'])} | {safe(row['subject_header'])} | [Open]({url}) |")
    lines += ['', 'Categories: Personal, Marketing, Newsletter, Receipt, Security, Notification, Social / community, Other or Unknown. '
              'Reply format: `ID=category; access=yes/no/unknown; human=yes/no/unknown; social-platform=yes/no/unknown`.', '']
    (base / 'V1-PHASE-3-PRIVATE-HELDBACK-REVIEW.md').write_text('\n'.join(lines))
    freeze = {'status': 'POLICY_FROZEN_BEFORE_NEW_REFERENCE_ANSWERS', 'contract_version': 'semantic-social-v2',
              'contract_source_sha256': hashlib.sha256(Path('src/main/java/com/tidymail/service/SemanticQualificationContract.java').read_bytes()).hexdigest(),
              'comparison_summary_sha256': hashlib.sha256((base / 'phase3-gmail-category-comparison-run-01/comparison-summary.json').read_bytes()).hexdigest(),
              'purpose_min_confidence': .85, 'purpose_min_probability': .85, 'signal_yes': .9, 'signal_no': .1,
              'input': 'Headers plus observed standard Gmail category labels, snapshotted once before dispatch',
              'pass2_enabled': False, 'fallback_enabled': False, 'production_adoption_enabled': False,
              'gate': 'Every reference-known resolved property correct; unknown references unscored; report unresolved coverage',
              'scope': 'One held-back semantic check only. Not qualification of importance, disposal, grouping or calendar finality.',
              'review_ids': [r['review_id'] for r in retained + fresh]}
    (base / 'phase3-heldback-policy-freeze.json').write_text(json.dumps(freeze, indent=2) + '\n')
    return {'retained_reviewed': len(retained), 'fresh_review_required': len(fresh), 'quarantined_ids': sorted(quarantine),
            'held_back_predictions': 0, 'policy_frozen': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.directory), sort_keys=True))
