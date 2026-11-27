# Review of Yap's group outer encryption layer

**Reviewer:** Codex, 27 November 2026, at the project owner's request.  
**Revision:** `67ac4ffbb1aeda7c7f245419dda5064eb0a8093b` (HEAD during this review).  
**Scope:** [proposal §3.4 and §5](../proposals/group-encryption-v1.md), [reference implementation and README](../../experiments/outer-layer-vectors/README.md), [tests](../../experiments/outer-layer-vectors/tests/vectors.rs), [committed vectors](../../experiments/outer-layer-vectors/vectors.txt), [Header::binding](../../core/src/envelope.rs), and the [response's “Outer-layer test vectors” section](2026-11-26-group-encryption-v1-response.md).

**Verdict: no serious cryptographic flaw found in the reviewed outer construction.** No Critical or High findings. The implemented byte-level operations match the proposal, and all five committed vectors independently recompute exactly. Two Medium findings concern incomplete validation, rather than a demonstrated confidentiality or authentication break. The reference is a vector generator, not an implementation of the complete receive/retry protocol.

**This is an AI-assisted review, not a professional security audit.** It does not approve the wider group protocol, mesh custody, persistence or deployment for sensitive use.

## Findings

### 1. Medium — the published-example and tampering assertions are narrower than advertised

**Locations:** `experiments/outer-layer-vectors/tests/vectors.rs:106–121` and `:30–56`; README's published-example and ciphertext-tampering claims; response lines 127–129.

The draft XChaCha known-answer test supplies the correct published inputs, but checks only the first 16 ciphertext bytes and the final 16-byte tag using `starts_with` and `ends_with`. It does not compare the full 114-byte ciphertext, total output length or all 130 output bytes together. This is not a complete known-answer comparison of the published example.

As a direct check of the assertion's strength, I changed ciphertext byte 20 in the independently computed published output while leaving its prefix and tag untouched. Both predicates used by the current Rust test still pass. This demonstrates an assertion gap, **not** an AEAD forgery: a correct decryptor rejects that corrupted ciphertext.

The general tampering test changes the last byte of the sealed output, which is a tag byte; it never flips a ciphertext byte or nonce byte. It also changes only the first AD byte. The separate changed-creation-time test changes both creation and expiry because it constructs a new header; it is not a test of creation time alone. The implementation itself was not found to accept these alterations: the independent checks described below rejected them.

**Fix:** compare the complete published ciphertext-plus-tag with `assert_eq!`, including its length. Add distinct ciphertext, nonce, tag, truncation and extension mutations; mutate envelope ID, destination label, sender hint, creation and expiry separately; and mutate both the label and hour portions of piece AD. Keep the existing format-tag test. The RFC 5869 test correctly checks the complete 42-byte OKM; adding the published PRK check would improve diagnostics but is not necessary to make its current claim true.

### 2. Medium — the receive window and re-wrapping policy have no executable conformance coverage

**Locations:** proposal §3.4 lines 255–275; `experiments/outer-layer-vectors/src/lib.rs:29–31`, `:66–97`, `:153–206`; `tests/vectors.rs:69–74`.

The reference exposes `hour_of`, context construction and generic seal/open helpers. It has no receiver clock, epoch-range validation, envelope expiry validation, random-nonce generation, pending-piece state or 20-hour re-wrapping operation. `open` accepts any supplied key and AD if authentication succeeds, irrespective of age. This is understandable for a vector generator, but it does not implement all the operational rules in §3.4.

The hour-boundary test checks that adjacent sending hours produce different keys. It does not test the inclusive `now_hour - 25` through `now_hour + 1` receive window, its rejection boundaries, or 3-/23-hour delayed delivery. None of the five vectors depicts an actual before/after re-wrap of the same inner message, preservation across batch changes, or the 20-hour threshold. The fixed nonce inputs are deliberate test fixtures and provide no evidence of CSPRNG use in a future caller.

Consequently, the committed suite cannot catch a future caller that uses the wrong hour, accepts out-of-window pieces, fails to refresh an expired piece, retains the old routing label after re-wrapping, or mistakenly re-encrypts the inner libsignal message. The specification's fixed byte rules are supported; the full receive/retry policy remains untested.

**Fix:** add explicit conformance cases or a small policy harness with a supplied clock and nonce source. Cover inclusive offsets -25 and +1, rejected offsets -26 and +2, low-hour arithmetic without underflow, delayed envelopes, exact lifetime/clock checks, and retries immediately before and at the chosen 20-hour refresh threshold. Include a piece re-wrapped into a new hour with the current label, a fresh nonce and identical inner bytes, then moved between batches. Keep generation-creation time inside `YGH1` unchanged: it is distinct from the outer wrapping hour. Document whether exactly 20 hours triggers refresh, rather than leaving the boundary implicit.

This is a verification gap, not a claim that the intentionally low-level `open` function is already being used unsafely by the app.

## 1. Text versus reference code

There is **no byte-level discrepancy** in the implemented operations:

| Rule | Reference behaviour | Result |
| --- | --- | --- |
| HKDF-SHA256 | Extract uses the exact UTF-8 salt `Yap outer layer v1` and the 32-byte label key; expand returns 32 bytes. | Matches. |
| Info layout | `kind[1] || group[16] || sender[32] || recipient[32] || hour_be[8]`, exactly 89 bytes. | Matches. |
| Kinds and group recipient | Group vectors use `0x01` and 32 zero recipient bytes; piece vectors use `0x02` and the intended recipient. | Matches. |
| Hour calculation | `ms / 3_600_000` with unsigned integer division. Envelope vector context uses its own `created_ms`; piece context and AD use its declared wrapping hour. | Matches. |
| Envelope AD | Exactly the 68 bytes from `Header::binding()`, with the label present once as destination and no hop byte. | Matches. |
| Piece AD | Exactly `label[16] || wrapping_hour_be[8]`, 24 bytes, independent of batch identity. | Matches. |
| Cipher and wire output | XChaCha20-Poly1305; `YGO1[4] || nonce[24] || ciphertext || tag[16]`. | Matches; overhead is 44 bytes. |
| Opening | Rejects outputs shorter than 44 bytes or with the wrong format tag, then verifies AEAD. | Consistent with the format. |

Differences in **scope/enforcement** are those in finding 2. Additionally, `info` accepts arbitrary kinds and recipient bytes, and `derive_key` accepts arbitrary info bytes; callers, not these generic helpers, must enforce the canonical schema. `seal` accepts a caller-provided nonce; all vectors use fixed nonces instead of randomness as the README explicitly explains. No function encodes or validates `YGH1` or `YGA1`: the piece fixtures encrypt arbitrary sample inner bytes. Those examples are valid outer-layer vectors, not valid complete hand-over/session test cases. The proposal implies a 32-byte derived key through the selected cipher; writing that output length explicitly would make the text more self-contained.

The reference and fixtures also do not implement routing-label derivation or test its output. Their chosen label bytes are inputs, not claims about the HMAC label scheme.

## 2. Independent recomputation

I used an in-memory Python 3 standard-library implementation of HMAC-SHA256 extract/expand, HChaCha20, ChaCha20 block generation and Poly1305. It neither calls the Rust reference nor uses its HKDF or AEAD libraries. The checker is reproduced in the appendix; it creates info, AD and fixture inputs independently and only reads `vectors.txt` for expected answers.

Before checking Yap, the implementation reproduced the complete PRK and 42-byte OKM from [RFC 5869 Appendix A.1](https://www.rfc-editor.org/rfc/rfc5869.html#appendix-A.1), and all 114 ciphertext bytes plus the 16-byte tag from [draft-irtf-cfrg-xchacha-03 Appendix A.3.1](https://www.ietf.org/archive/id/draft-irtf-cfrg-xchacha-03.txt). These are independent checks against published data, not a call to `render()`.

All five Yap vectors match in **every listed field**: info, key, nonce, AD, plaintext and sealed output. `NOON_MS` is correctly 2026-11-27 12:00:00 UTC; its hour is decimal `498828`, hexadecimal `0000000000079c8c`. The previous-millisecond vector uses `498827`.

The common extract result is:

```text
PRK = 82b8fcbea8add9800cf6aa4c6b7bbf5201c5d75c0aa73f9d164620c8e6749b86
K   = HMAC-SHA256(PRK, info || 01)
```

For the group envelope:

```text
key    = aa2fd4e2f6292f35ba9e239eba185d896202d4406273ece7826559b4fcd8d771
sealed = 59474f31616161616161616161616161616161616161616161616161f40f30b07ba790a387a26517c9c391816ee658a99f53655a7380eef1b62a4ab941
```

For the Alice-to-Bob piece:

```text
key    = 7da94e212860088af35ff975368e14162164fe81a9b8dfb345ebb5a4a09a4134
sealed = 59474f31636363636363636363636363636363636363636363636363a2666f0edf4d5e1a60394abb29fc80c206845adc583214f462a68b1c3bd07207422b35d1b239f8
```

| Vector | Info bytes | AD bytes | Plaintext bytes | Sealed bytes | Independent result |
| --- | --- | --- | --- | --- | --- |
| Group envelope | 89 | 68 | 17 | 61 | Exact match |
| Group, previous millisecond | 89 | 68 | 17 | 61 | Exact match |
| Piece Alice → Bob | 89 | 24 | 23 | 67 | Exact match |
| Piece Bob → Alice | 89 | 24 | 23 | 67 | Exact match |
| Empty piece | 89 | 24 | 0 | 44 | Exact match |

Additional independent checks rejected mutations to the nonce, ciphertext, tag, format marker, all immutable header fields, piece label/hour, and each KDF context field. An independent +21-hour re-wrap with a new label and nonce opened to the unchanged inner bytes under the new context and failed under the old context. These checks support the construction; they do not substitute for finding 2's missing tests of the reference's caller policy.

## 3. Construction and security limits

**Key schedule and separation.** HKDF extract/expand is used normally. A fixed public salt is suitable here; it supplies protocol separation, not secret entropy. The label key must be genuinely secret and have the specified entropy. Fixed-width fields make the info encoding unambiguous. Kind, group, ordered sender/recipient and hour prevent reflection and cross-group/type/epoch reuse of the same derived key. The opposite-direction fixtures deliberately reuse the nonce but derive different keys; that is safe. A fresh group label key per generation supplies further separation even though the generation number is not in info.

**Nonces.** Independent uniformly random 24-byte nonces from a CSPRNG are a reasonable choice for XChaCha20-Poly1305, as described in the [XChaCha draft's motivation](https://www.ietf.org/archive/id/draft-irtf-cfrg-xchacha-03.txt). Full nonce collision probability for q encryptions under one key is approximately `q(q-1) / 2^193`. This is probabilistic uniqueness, not misuse resistance: cloned deterministic RNG state or a faulty generator can still repeat a key/nonce pair. Fixed vector nonces must never become production defaults. Repeating stored ciphertext for a retry is not another encryption and does not create a new nonce-use event.

**Envelope context.** Binding authenticates version, message ID, destination, sender hint, creation and expiry. A relay cannot alter them, choose another hour or move the ciphertext to another authenticated context without failing verification. The hop field is intentionally mutable; AEAD cannot enforce a hop budget against a malicious relay. The encoded ciphertext-length field is not in `Header::binding`, but the envelope decoder requires the declared length to match the bytes, and AEAD authenticates the complete ciphertext. No unchecked length-rebinding attack was found.

**Piece context.** Group, peer identities, direction and hour are bound through key derivation; label and hour are also bound through AD. Movement between batches is deliberately allowed, while movement to another peer/group/hour/label is rejected when the receiver derives and verifies the correct context. Caller validation must check the inner `YGH1` group, recipient, generation, kind, event references and distribution data against the authenticated session and membership state. The generation-creation hour is authenticated inside libsignal; it is not the outer wrapping hour and must survive re-wrapping unchanged.

**Freshness and replay.** The inclusive hour window is a coarse outer-key acceptance rule, not a precise 24-hour message-expiry check. Hour rounding can admit a piece almost 26 hours old; a future hour can be almost two hours ahead of real time. Envelopes must independently pass their exact expiry/lifetime and clock checks. Pieces need the stated duplicate/generation/session rules. Authentication alone does not reject replay. The updated saved-inner-digest acknowledgement rule in §3.6 is relevant when re-wrapping repeats an already accepted libsignal ciphertext; the outer vector suite does not exercise it.

**Re-wrapping.** Re-encrypting unchanged inner ciphertext under the current epoch key with a fresh nonce is sound at the outer AEAD level. Refresh the routing label consistently with the new wrapping hour, bind it in AD, preserve the inner message and generation-creation hour, and persist the new wrapper before retrying it. The 20-hour schedule leaves margin within the 25-hour window if pending retries are actually serviced; a phone sleeping for longer must refresh before transmitting. This does not restore expired pre-keys, deleted inner session state or an erased group generation; those are separate recovery concerns.

**Relay linkability.** The layer hides libsignal's distribution IDs, chain counters, ratchet public keys and initial identity keys from relays without the label secret. It does not deliver general unlinkability: unchanged retries have identical envelope IDs, ciphertext, nonces and labels; identical pieces can be recognised across batches until re-wrapped; batch membership, size, timing, paths and hourly labels remain observable. Re-wrapping changes the ciphertext, but a nearly unchanged recipient set and timing can still link the old and new batches. The fixed `sender_hint` values in these fixtures are examples; a production integration must follow the rotating-label proposal's random hint rule. Exposure of a label key permits deriving its past and future hourly outer keys until that secret changes, so the outer layer does not provide forward secrecy for metadata by itself.

Group recipients share the relevant sender label secret, so outer authentication alone is not proof of authorship against a malicious group member. Inner sender-key signatures, pairwise authentication and membership checks must remain mandatory. Future wire versions should preserve domain separation rather than assuming a different literal format marker alone creates a different cryptographic context.

## 4. What the tests establish

The RFC 5869 test uses the correct IKM, salt, info and complete 42-byte expected OKM. It validates the library invocation for that published case, but is not itself a test of Yap's salt/layout; committed vectors and the independent recomputation cover those.

The XChaCha test uses the correct draft key, nonce, AAD and plaintext. Its partial comparison is finding 1; the independently computed full output matches the published case. The committed-vector test is a useful regression check, but generation and checking use the same code, so it is not independent evidence on its own.

The pair-direction, kind-separation and hop-mutation claims are supported by their actual tests. The kind-separation test changes only kind while keeping recipient Bob even for the group case: it proves kind affects the KDF, but its group input is not canonical. The canonical group vector uses a zero recipient as required. The creation-time test and tampering scope have the qualifications in finding 1. The hour test proves a key boundary, not an acceptance-window policy. The CI matrix includes this crate and invokes locked tests; this review did not inspect a remote CI run.

## 5. Requested test run and file boundary

Ran exactly `cargo test --locked` in `experiments/outer-layer-vectors/` using the installed Cargo executable. **Exit code 0: all 9 integration tests passed.** Library, binary and documentation targets each had zero tests; none failed.

Only this new report was added. No existing source, proposal, response, vector, test or lockfile was edited. The independent Python checks ran through standard input with bytecode writing disabled, without creating a script file. Existing untracked mesh audit/simulation work was left untouched; the requested Cargo run used normal build artefacts.

## Appendix: independent checker

Run the following with Python 3 from the repository root. It uses only the standard library. This is review-only checking code, not a proposed production cipher implementation.

```python
import hashlib, hmac, struct, pathlib, re, datetime
MASK = (1 << 32) - 1
def rot(x,n): return ((x << n) | (x >> (32-n))) & MASK
def qr(x,a,b,c,d):
    x[a]=(x[a]+x[b])&MASK; x[d]=rot(x[d]^x[a],16)
    x[c]=(x[c]+x[d])&MASK; x[b]=rot(x[b]^x[c],12)
    x[a]=(x[a]+x[b])&MASK; x[d]=rot(x[d]^x[a],8)
    x[c]=(x[c]+x[d])&MASK; x[b]=rot(x[b]^x[c],7)
def rounds(x):
    for _ in range(10):
        for t in [(0,4,8,12),(1,5,9,13),(2,6,10,14),(3,7,11,15),
                  (0,5,10,15),(1,6,11,12),(2,7,8,13),(3,4,9,14)]:
            qr(x,*t)
    return x
def words(b): return list(struct.unpack('<'+'I'*(len(b)//4),b))
def packed(x): return struct.pack('<'+'I'*len(x),*x)
def block(k,n,c):
    s=words(b'expand 32-byte k'+k)+[c]+words(n)
    z=rounds(s[:])
    return packed([(a+b)&MASK for a,b in zip(s,z)])
def xseal(k,n,ad,msg):
    z=rounds(words(b'expand 32-byte k'+k+n[:16]))
    sub=packed([z[i] for i in [0,1,2,3,12,13,14,15]])
    n12=b'\0'*4+n[16:]
    stream=b''.join(block(sub,n12,i+1) for i in range((len(msg)+63)//64))
    ct=bytes(a^b for a,b in zip(msg,stream))
    pk=block(sub,n12,0)[:32]
    r=int.from_bytes(pk[:16],'little') & 0x0ffffffc0ffffffc0ffffffc0fffffff
    s=int.from_bytes(pk[16:],'little')
    data=ad+b'\0'*((-len(ad))%16)+ct+b'\0'*((-len(ct))%16)+struct.pack('<QQ',len(ad),len(ct))
    acc=0
    for i in range(0,len(data),16):
        acc=((acc+int.from_bytes(data[i:i+16]+b'\x01','little'))*r)%((1<<130)-5)
    return ct+((acc+s)%(1<<128)).to_bytes(16,'little')
def hkdf(salt,ikm,info,length=32):
    prk=hmac.digest(salt,ikm,'sha256'); t=b''; out=b''
    for i in range(1,(length+31)//32+1):
        t=hmac.digest(prk,t+info+bytes([i]),'sha256'); out+=t
    return prk,out[:length]
prk,okm=hkdf(bytes(range(13)),b'\x0b'*22,bytes(range(0xf0,0xfa)),42)
assert prk.hex()=='077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5'
assert okm.hex()=='3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865'
published_ct=bytes.fromhex(
'bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb'
'731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452'
'2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9'
'21f9664c97637da9768812f615c68b13b52e'+'c0875924c1c7987947deafd8780acf49')
kat=xseal(bytes(range(0x80,0xa0)),bytes(range(0x40,0x58)),bytes.fromhex('50515253c0c1c2c3c4c5c6c7'),
b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")
assert kat==published_ct
print('Independent RFC 5869 PRK + 42-byte OKM: PASS')
print('Independent draft-03 full ciphertext + tag (130 bytes): PASS')
text=pathlib.Path('experiments/outer-layer-vectors/vectors.txt').read_text()
sections={}
for m in re.finditer(r'^\[([^\]]+)\]\n(.*?)(?=^\[|\Z)',text,re.M|re.S):
    sections[m.group(1)]={k:bytes.fromhex(v) for k,v in re.findall(r'^(\w+)\s*=\s*([0-9a-f]*)$',m.group(2),re.M)}
noon=1795780800000
print('NOON_MS:',datetime.datetime.fromtimestamp(noon/1000,datetime.timezone.utc).isoformat(),'hour=',noon//3600000)
for i,(name,v) in enumerate(sections.items()):
    group=i<2; ms=noon-(i==1); hour=ms//3600000
    sender=b'\xbb'*32 if i==3 else b'\xaa'*32
    recipient=b'\0'*32 if group else (b'\xaa'*32 if i==3 else b'\xbb'*32)
    info=bytes([1 if group else 2])+b'\x22'*16+sender+recipient+hour.to_bytes(8,'big')
    ad=(b'YAP\x01'+b'\x01'*16+b'\x33'*16+b'\x44'*16+struct.pack('>QQ',ms,ms+86400000)) if group else b'\x55'*16+hour.to_bytes(8,'big')
    nonce=bytes([0x61+i if i<3 else (0x63 if i==3 else 0x64)])*24
    msg=b'Yap group message' if group else (b'' if i==4 else b'inner libsignal message')
    prk,key=hkdf(b'Yap outer layer v1',b'\x11'*32,info)
    sealed=b'YGO1'+nonce+xseal(key,nonce,ad,msg)
    actual={'info':info,'key':key,'nonce':nonce,'ad':ad,'plaintext':msg,'sealed':sealed}
    for field,value in actual.items(): assert value==v[field],(name,field,value.hex(),v[field].hex())
    print(name+': PASS all six fields; bytes info/ad/plaintext/sealed=',len(info),len(ad),len(msg),len(sealed))
    if i in [0,2]:
        print('prk='+prk.hex()); print('key='+key.hex()); print('sealed='+sealed.hex())
print('ALL FIVE COMMITTED VECTORS INDEPENDENTLY RECOMPUTED')

def xopen(key,nonce,ad,body):
    if len(body)<16: return None
    ct=body[:-16]
    stream=xseal(key,nonce,ad,b'\0'*len(ct))[:len(ct)]
    msg=bytes(a^b for a,b in zip(ct,stream))
    return msg if hmac.compare_digest(xseal(key,nonce,ad,msg),body) else None
def opening(key,ad,sealed):
    if len(sealed)<44 or sealed[:4]!=b'YGO1': return None
    return xopen(key,sealed[4:28],ad,sealed[28:])
v=sections['group envelope']
for field,off in [('format',0),('nonce',4),('ciphertext',28),('tag',-1)]:
    changed=bytearray(v['sealed']); changed[off]^=1
    assert opening(v['key'],v['ad'],bytes(changed)) is None,field
for field,off in [('envelope ID',4),('label',20),('sender hint',36),('creation',59),('expiry',67)]:
    ad=bytearray(v['ad']); ad[off]^=1
    assert opening(v['key'],bytes(ad),v['sealed']) is None,field
p=sections['piece, Alice to Bob']
for field,off in [('kind',0),('group',1),('sender',17),('recipient',49),('hour',88)]:
    changed=bytearray(p['info']); changed[off]^=1
    _,key=hkdf(b'Yap outer layer v1',b'\x11'*32,bytes(changed))
    assert opening(key,p['ad'],p['sealed']) is None,field
for off in [0,23]:
    ad=bytearray(p['ad']); ad[off]^=1
    assert opening(p['key'],bytes(ad),p['sealed']) is None
new_hour=noon//3600000+21
new_info=p['info'][:-8]+new_hour.to_bytes(8,'big')
_,newkey=hkdf(b'Yap outer layer v1',b'\x11'*32,new_info)
newad=b'\x56'*16+new_hour.to_bytes(8,'big')
newseal=b'YGO1'+b'\x65'*24+xseal(newkey,b'\x65'*24,newad,p['plaintext'])
assert opening(newkey,newad,newseal)==p['plaintext']
assert opening(p['key'],p['ad'],newseal) is None
print('Independent ciphertext/nonce/tag/format/header/context mutation checks: PASS')
print('Independent +21-hour piece rewrap with fresh label, key and nonce: PASS')
mut=bytearray(kat); mut[20]^=1
assert mut.hex().startswith('bd6d179d3e83d43b9576579493c0e939')
assert mut.hex().endswith('c0875924c1c7987947deafd8780acf49')
assert bytes(mut)!=published_ct
print('Current draft-KAT prefix/suffix assertions also accept a deliberately corrupt middle ciphertext byte')
```
