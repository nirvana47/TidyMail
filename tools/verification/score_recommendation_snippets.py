"""Read-only paired development scoring. Unknown references remain unscored; never enables adoption."""
from pathlib import Path
from decimal import Decimal
import json
import math
import statistics
import sys

def read(path):
    return json.loads(path.read_text(), parse_float=Decimal)

def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values)*fraction)-1)]

def main(folder):
    ledger=read(folder/'ledger.json')
    if ledger['status']!='COMPLETE':
        raise ValueError('Score only a complete development run')
    pairs={}
    costs={'HEADERS':Decimal(0),'SNIPPET':Decimal(0)}
    times={'HEADERS':[],'SNIPPET':[]}
    models=set()
    providers=set()
    request_count=0
    for attempt in ledger['attempts']:
        if attempt['state']!='REPORTED' or not attempt.get('validated'):
            raise ValueError('Unsettled or unvalidated request')
        index=attempt['sequence']
        request=read(folder/f'request-{index}.json')
        response=read(folder/f'response-{index}.json')
        result=read(folder/f'result-{index}.json')
        if len(request['state'])!=1 or len(request['questions'])!=13:
            raise ValueError('Unexpected request membership or contract')
        if response['usage']['cost']!=attempt['reported_usd']:
            raise ValueError('Response/ledger charge mismatch')
        variant=attempt['variant']
        if result['variant']!=variant or result['review_id']!=attempt['review_id']:
            raise ValueError('Response association mismatch')
        case=pairs.setdefault(result['review_id'],{})
        if variant in case:
            raise ValueError('Duplicate paired arm')
        case[variant]=(request,result)
        costs[variant]+=attempt['reported_usd']
        times[variant].append(attempt['request_ms'])
        models.add(response['model'])
        providers.add(response['provider'])
        request_count+=1
    if request_count!=22 or len(pairs)!=11 or len(models)!=1 or {p.lower() for p in providers}!={'typesafe'}:
        raise ValueError('Incomplete matched run or changed provider/model')
    total=sum(costs.values())
    if total!=ledger['reported_usd']:
        raise ValueError('Total charge mismatch')
    metrics={}
    for variant in costs:
        metrics[variant]={
            'purpose':dict(reviewed=0,correct_resolved=0,incorrect_resolved=0,unresolved=0,raw_selected_correct=0),
            'security':dict(reviewed=0,correct_resolved=0,incorrect_resolved=0,unresolved=0),
            'human_correspondence':dict(reviewed=0,correct_resolved=0,incorrect_resolved=0,unresolved=0),
            'diagnostic_actions':{},'purpose_resolved_all':0,
            'reported_usd':str(costs[variant]),'provider_ms_p50':statistics.median(times[variant]),
            'provider_ms_p95':percentile(times[variant],.95),
        }
    changes=[]
    case_rows=[]
    for review,arms in sorted(pairs.items()):
        if set(arms)!={'HEADERS','SNIPPET'}:
            raise ValueError('Missing paired arm')
        baseline,base_result=arms['HEADERS']
        richer,rich_result=arms['SNIPPET']
        stripped=json.loads(json.dumps(richer,default=str))
        snippet=stripped['state']['email_0'].pop('snippet')
        if stripped!=baseline or not snippet['text'] or len(snippet['text'].encode())>1024:
            raise ValueError('Inputs differ beyond the actual bounded snippet')
        if base_result['prior_reviewed_reference']!=rich_result['prior_reviewed_reference']:
            raise ValueError('Paired references changed')
        output={'review_id':review,'prior_reviewed_reference':base_result['prior_reviewed_reference'],'arms':{}}
        for variant,(_,result) in arms.items():
            m=metrics[variant]
            diagnostic=result['diagnostic']
            if diagnostic['shadowOnly'] is not True:
                raise ValueError('Non-shadow result rejected')
            recommendation=diagnostic['recommendation']
            action=recommendation['action']
            m['diagnostic_actions'][action]=m['diagnostic_actions'].get(action,0)+1
            m['purpose_resolved_all']+=int(diagnostic['purposeResolved'])
            ref=result['prior_reviewed_reference'] or {}
            selected=result['judgment']['purpose']['selected']
            if ref.get('purpose') is not None:
                p=m['purpose'];p['reviewed']+=1
                p['raw_selected_correct']+=int(selected==ref['purpose'])
                p['unresolved' if not diagnostic['purposeResolved'] else 'correct_resolved' if selected==ref['purpose'] else 'incorrect_resolved']+=1
            for metric,property_name,reference in [('security','SECURITY','security_access'),('human_correspondence','HUMAN_CORRESPONDENCE','human_correspondence')]:
                if isinstance(ref.get(reference),bool):
                    p=m[metric];p['reviewed']+=1
                    actual=diagnostic['properties'][property_name]
                    p['unresolved' if actual=='UNKNOWN' else 'correct_resolved' if (actual=='PRESENT')==ref[reference] else 'incorrect_resolved']+=1
            output['arms'][variant]={
                'selected_purpose':selected,'purpose_resolved':diagnostic['purposeResolved'],
                'security':diagnostic['properties']['SECURITY'],'human':diagnostic['properties']['HUMAN_CORRESPONDENCE'],
                'action':action,'reason':recommendation['reason'],'attention':recommendation['attention'],
                'facets':recommendation['facets'],
            }
        if output['arms']['HEADERS']!=output['arms']['SNIPPET']:
            changes.append(review)
        case_rows.append(output)
    summary=dict(status='MATCHED_DEVELOPMENT_COMPARISON_NO_ADOPTION',messages=11,requests=22,
                 exact_pairs_verified=True,served_models=sorted(models),reported_usd=str(total),metrics=metrics,
                 changed_diagnostic_cases=changes,cases=case_rows,
                 limits=['Only two prior forward references have reviewed purpose/security/human labels.',
                         'Nine other real purposes and all new action/protected-facet references remain unscored.',
                         'These are exposed development/regression cases, not an independent held-back gate.',
                         'No production classification, policy adoption or mailbox action follows from these diagnostics.'])
    (folder/'comparison-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps({k:summary[k] for k in ['status','messages','requests','reported_usd','metrics','changed_diagnostic_cases']},indent=2))

if __name__=='__main__':
    main(Path(sys.argv[1]))
