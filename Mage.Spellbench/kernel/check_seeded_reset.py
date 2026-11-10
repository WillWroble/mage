"""Checks the patched kernel: x_lockstep_reset deals each seat the top seven of its given library order (seen in
that seat's own v2 observation), whichever seat starts, and x_lockstep_arrange_library is accepted.

    python check_seeded_reset.py <spellbench checkout> <mtg-kernel checkout, built>
"""
import json, random, subprocess, sys
S, K = sys.argv[1], sys.argv[2]
cat = json.load(open(K + '/pauper-v2-catalog.json'))
decks = {d['catalog_id']: [e['name'] for e in d['decklist'] for _ in range(e['count'])] for d in cat['catalog']}
sys.path.insert(0, S + '/python')
from spellbench.digests import deck_id, card_name_domain
from spellbench.messages import ResetRequest, Rules, WireDeck, CardNameDomain
ids = {d['catalog_id']: deck_id(d['decklist']) for d in cat['catalog']}
def engine():
    return subprocess.Popen([sys.executable, S + '/integrations/mtg_kernel/kernel_engine_v2.py', '--bridge', K + '/target/release/agent_bridge_v1',
                      '--catalog', K + '/pauper-v2-catalog.json', '--source-revision', '92ec2393db34bea805a85991163458b9ff890f57', '--known-cards'],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
p = engine()
def req(o):
    p.stdin.write(json.dumps(o) + '\n'); p.stdin.flush(); return json.loads(p.stdout.readline())
hello = req({'protocol': 'spellbench/v2', 'request_type': 'hello', 'request_id': 'h', 'protocol_minor': 0})
print(hello['response_type'], hello.get('error'))
for first in ('p0', 'p1'):
    p = engine(); req({'protocol': 'spellbench/v2', 'request_type': 'hello', 'request_id': 'h', 'protocol_minor': 0})
    rng = random.Random(first)
    orders = [rng.sample(decks['Faeries'], 60), rng.sample(decks['Burn'], 60)]
    names = [n for d in ('Faeries', 'Burn') for n in decks[d]]
    rules = Rules(opponent_decklist='hidden', mulligan='none', starting_player='host_assigned', starting_seat=first,
                  card_name_domain=CardNameDomain.from_json(card_name_domain(names)),
                  extensions=('x_kernel_flat_v4', 'x_public_history_v1'), probe=False)
    seats = tuple(WireDeck.from_json({'catalog_id': d, 'deck_id': ids[d]}) for d in ('Faeries', 'Burn'))
    reset = ResetRequest(request_id='r' + first, game_id='g' + first, format='pauper-bo1', seats=seats, rules=rules,
                         game_secret='11' * 32, max_decisions=100000, max_steps=100000).to_json()
    r = req({**reset, 'request_type': 'x_lockstep_reset', 'library_orders': orders})
    if r.get('response_type') != 'decision':
        print('reset', first, r); break
    obs = r['seat_decision']['observation']
    me = [x for x in obs['players'] if x['seat'] == obs['viewer']][0]
    seat = obs['viewer']; idx = 0 if seat == 'p0' else 1
    print('first', first, 'viewer', seat, 'hand ok:', sorted(c['card_name'] for c in me['hand']) == sorted(orders[idx][:7]))
    print(' arrange:', req({'protocol': 'spellbench/v2', 'request_type': 'x_lockstep_arrange_library', 'request_id': 'a',
                            'game_id': 'g' + first, 'seat': 'p1', 'library': list(reversed(orders[1][7:]))})['response_type'])
