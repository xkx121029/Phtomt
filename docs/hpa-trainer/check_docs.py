import json, time

d = json.load(open('/data/task_docs/index.json', encoding='utf-8'))
items = sorted(d.values(), key=lambda x: x.get('updated_at', 0), reverse=True)
print('docs_count=', len(items))
for it in items[:6]:
    ts = it.get('updated_at', 0)
    print(it.get('task_id'), 'gen=', it.get('generation'), 'pv=', it.get('policy_version'),
          'steps=', it.get('step_count'), 'updated=', time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(ts)))
