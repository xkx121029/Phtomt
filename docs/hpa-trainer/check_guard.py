import json, glob, os

paths = sorted(glob.glob('/data/runs/gen*/results.jsonl'))
print('files:', len(paths))
for p in paths[-3:]:
    n = 0
    flag = 0
    with open(p, encoding='utf-8', errors='ignore') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except Exception:
                continue
            n += 1
            judge = d.get('judge') or {}
            txt = json.dumps(judge, ensure_ascii=False)
            if 'numeric inconsistency' in txt:
                flag += 1
                print('HIT', os.path.basename(p), d.get('id'), 'steps=', d.get('execution', {}).get('steps'),
                      'success=', d.get('is_success'))
                print('  judge:', txt[:300])
            if n % 1 == 0 and d.get('id') in ('alipay.CheckBalance',):
                print('CB', os.path.basename(p), 'steps=', d.get('execution', {}).get('steps'),
                      'stop=', d.get('execution', {}).get('stop_reason'),
                      'success=', d.get('is_success'),
                      'answer=', d.get('execution', {}).get('agent_answer'),
                      'judge=', txt[:220])
    print(p, 'lines=', n, 'guard_hits=', flag)
