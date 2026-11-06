import re, os, sys

root = 'shared/src/commonMain/kotlin'
files = []
for dp, _, fn in os.walk(root):
    for f in fn:
        if f.endswith('.kt'):
            files.append(os.path.join(dp, f))


def blank(src):
    out = list(src)
    i = 0
    n = len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = ' '
            i = j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if src[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        if src[i] == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            for k in range(i, j):
                out[k] = ' '
            i = j
            continue
        i += 1
    return ''.join(out)


def match_close(s, i, o, c):
    d = 0
    while i < len(s):
        if s[i] == o:
            d += 1
        elif s[i] == c:
            d -= 1
            if d == 0:
                return i
        i += 1
    return -1


code = {f: blank(open(f).read()) for f in files}

decl_re = re.compile(r'sealed\s+interface\s+(\w+)')
types = {}
for f, c in code.items():
    for m in decl_re.finditer(c):
        name = m.group(1)
        k = m.end()
        while k < len(c) and c[k] in ' \t':
            k += 1
        supers = ''
        body = None
        if k < len(c) and c[k] == ':':
            e = k
            while e < len(c) and c[e] not in '{\n':
                e += 1
            supers = c[k + 1:e].strip()
            if e < len(c) and c[e] == '{':
                body = e
        elif k < len(c) and c[k] == '{':
            body = k
        types.setdefault(name, []).append((f, supers, body))


def nms(s):
    return set(re.findall(r'\b(\w+)\b', s))


intent = set()
changed = True
while changed:
    changed = False
    for name, decls in types.items():
        if name in intent:
            continue
        for f, su, b in decls:
            n = nms(su)
            if 'MviIntent' in n or (n & intent):
                intent.add(name)
                changed = True
                break

cases = {}
dr2 = re.compile(r'(?:data\s+)?(?:class|object)\s+(\w+)')
for name in intent:
    cs = set()
    for f, su, b in types[name]:
        if b is None:
            continue
        close = match_close(code[f], b, '{', '}')
        bt = code[f][b + 1:close]
        for m in dr2.finditer(bt):
            dd = 0
            for ch in bt[:m.start()]:
                if ch == '{':
                    dd += 1
                elif ch == '}':
                    dd -= 1
            if dd == 0:
                cs.add(m.group(1))
    cases[name] = cs


def resolve(case, chain):
    cands = [t for t in intent if case in cases[t] and (not chain or t in chain)]
    return cands[0] if len(cands) == 1 else None


def cond_run(text, arrow):
    i = arrow - 1
    while i >= 0:
        ch = text[i]
        if ch.isalnum() or ch in '_.':
            i -= 1
            continue
        if ch.isspace():
            j = i
            while j >= 0 and text[j].isspace():
                j -= 1
            if j >= 0 and (text[j].isalnum() or text[j] in '_.'):
                i = j
                continue
            break
        if ch == ',':
            i -= 1
            continue
        break
    return text[i + 1:arrow].strip()


def norm(s):
    return re.sub(r'\s+', ' ', s).strip()


def split_conditions(cond):
    parts = []
    cur = ''
    d = 0
    for ch in cond:
        if ch in '([{':
            d += 1
        elif ch in ')]}':
            d -= 1
        if ch == ',' and d == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
    parts.append(cur)
    return [norm(p) for p in parts if norm(p)]


when_re = re.compile(r'\bwhen\s*\(')
viol = []
dispatchers = []
for f, c in code.items():
    for m in when_re.finditer(c):
        op = c.index('(', m.start())
        cl = match_close(c, op, '(', ')')
        subject = c[op + 1:cl].strip()
        funpos = c.rfind('fun ', 0, m.start())
        if funpos < 0:
            continue
        p1 = c.find('(', funpos)
        p2 = match_close(c, p1, '(', ')')
        params = c[p1 + 1:p2]
        ptypes = {}
        for p in params.split(','):
            if ':' in p:
                nm = p.split(':')[0].strip().split()[-1]
                ty = p.split(':', 1)[1].strip()
                ptypes[nm] = ty
        if subject not in ptypes:
            continue
        ty = ptypes[subject]
        tsimple = ty.split('<')[0].strip().split('.')[-1]
        if tsimple not in intent:
            continue
        wb = c.index('{', cl)
        wc = match_close(c, wb, '{', '}')
        body = c[wb + 1:wc]
        # find arrows at depth 0
        arrows = []
        d = 0
        i = 0
        while i < len(body):
            ch = body[i]
            if ch in '([{':
                d += 1
            elif ch in ')]}':
                d -= 1
            elif ch == '-' and d == 0 and i + 1 < len(body) and body[i + 1] == '>':
                arrows.append(i)
                i += 2
                continue
            i += 1
        entries = []
        for a in arrows:
            cond = cond_run(body, a)
            entries.append({'cond': cond, 'arrow': a, 'conds': split_conditions(cond)})
        # bodies
        for idx, e in enumerate(entries):
            start = e['arrow'] + 2
            end = entries[idx + 1]['arrow'] - len(entries[idx + 1]['cond']) if idx + 1 < len(entries) else len(body)
            if end < start:
                end = start
            e['body'] = norm(body[start:end])
        dispatchers.append((f, tsimple, entries))
        # violation A
        for e in entries:
            ts = []
            for cd in e['conds']:
                cc = cd[3:].strip() if cd.startswith('is ') else cd
                if not re.fullmatch(r'[\w.]+', cc):
                    continue
                segs = cc.split('.')
                t = resolve(segs[-1], segs[:-1])
                if t:
                    ts.append((t, segs[-1]))
            from collections import Counter
            for t, cnt in Counter(t for t, _ in ts).items():
                if cnt > 1:
                    viol.append(('A-same-branch', f, tsimple, t, [c for tt, c in ts if tt == t]))
        # violation B
        bytype = {}
        for e in entries:
            for cd in e['conds']:
                cc = cd[3:].strip() if cd.startswith('is ') else cd
                if not re.fullmatch(r'[\w.]+', cc):
                    continue
                segs = cc.split('.')
                t = resolve(segs[-1], segs[:-1])
                if t:
                    bytype.setdefault(t, []).append((segs[-1], e['body']))
        for t, lst in bytype.items():
            for i in range(len(lst)):
                for j in range(i + 1, len(lst)):
                    if lst[i][0] != lst[j][0] and lst[i][1] and lst[i][1] == lst[j][1]:
                        viol.append(('B-same-handler', f, tsimple, t, [lst[i][0], lst[j][0]], lst[i][1]))

print('dispatchers:', len(dispatchers))
for f, t, entries in dispatchers:
    print(' ', f.split('/')[-1], '->', t)
    for e in entries:
        print('      ', e['conds'], '=>', e['body'][:70])
print()
print('violations:', len(viol))
for v in viol:
    print(v)
