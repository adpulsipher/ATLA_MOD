#!/usr/bin/env python3
"""Writes the empty 16x8x16 (stone floor) structure used by atla_gates' GameTests.

Output: atla-gates/src/gametest/resources/data/atla_gates/structures/empty.nbt
"""
import gzip
import os
import struct

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def w_str(s):
    b = s.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def payload(tag_type, value):
    if tag_type == TAG_INT:
        return struct.pack('>i', value)
    if tag_type == TAG_STRING:
        return w_str(value)
    if tag_type == TAG_LIST:
        elem_type, items = value
        out = struct.pack('>bi', elem_type if items else TAG_END, len(items))
        return out + b''.join(payload(elem_type, i) for i in items)
    if tag_type == TAG_COMPOUND:
        out = b''
        for name, (t, v) in value.items():
            out += struct.pack('>b', t) + w_str(name) + payload(t, v)
        return out + struct.pack('>b', TAG_END)
    raise ValueError(tag_type)


def main():
    size = (16, 8, 16)
    blocks = []
    for x in range(size[0]):
        for z in range(size[2]):
            blocks.append({'pos': (TAG_LIST, (TAG_INT, [x, 0, z])), 'state': (TAG_INT, 0)})
    root = {
        'DataVersion': (TAG_INT, 3465),  # 1.20.1
        'size': (TAG_LIST, (TAG_INT, list(size))),
        'palette': (TAG_LIST, (TAG_COMPOUND, [{'Name': (TAG_STRING, 'minecraft:stone')}])),
        'blocks': (TAG_LIST, (TAG_COMPOUND, blocks)),
        'entities': (TAG_LIST, (TAG_COMPOUND, [])),
    }
    data = struct.pack('>b', TAG_COMPOUND) + w_str('') + payload(TAG_COMPOUND, root)
    out = os.path.join(os.path.dirname(__file__), '..', 'atla-gates', 'src', 'gametest', 'resources',
                       'data', 'atla_gates', 'structures', 'empty.nbt')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, 'wb') as f:
        f.write(data)
    print('wrote', os.path.abspath(out))


if __name__ == '__main__':
    main()
