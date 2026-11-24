# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""Minimal s-expression reader/writer for KiCad files.

Written for Pulsar. Atoms are str; quoted strings are Q (a str subclass) so
they round-trip with their quotes. Lists are Python lists.
"""


class Q(str):
    """A quoted string atom."""


def parse(text):
    pos = 0
    n = len(text)
    stack = [[]]
    while pos < n:
        c = text[pos]
        if c in " \t\r\n":
            pos += 1
        elif c == "(":
            stack.append([])
            pos += 1
        elif c == ")":
            done = stack.pop()
            stack[-1].append(done)
            pos += 1
        elif c == '"':
            pos += 1
            buf = []
            while text[pos] != '"':
                if text[pos] == "\\":
                    nxt = text[pos + 1]
                    buf.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(nxt, nxt))
                    pos += 2
                else:
                    buf.append(text[pos])
                    pos += 1
            pos += 1
            stack[-1].append(Q("".join(buf)))
        else:
            start = pos
            while pos < n and text[pos] not in " \t\r\n()":
                pos += 1
            stack[-1].append(text[start:pos])
    assert len(stack) == 1, "unbalanced s-expression"
    return stack[0]


def _atom(a):
    if isinstance(a, Q):
        s = a.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
        return '"' + s + '"'
    if isinstance(a, float):
        s = f"{a:.4f}".rstrip("0").rstrip(".")
        return "0" if s in ("-0", "") else s
    return str(a)


# Lists whose children are all atoms/short lists print on one line.
_INLINE = {"at", "size", "xy", "start", "end", "mid", "length", "width", "type",
           "hide", "offset", "uuid", "lib_id", "unit", "justify", "reference",
           "page", "color", "thickness", "bold", "italic", "dnp", "in_bom",
           "on_board", "exclude_from_sim", "in_pos_files", "fields_autoplaced",
           "embedded_fonts", "duplicate_pin_numbers_are_jumpers", "show_name",
           "do_not_autoplace", "version", "generator", "generator_version",
           "paper", "title", "date", "rev", "company", "comment", "extends",
           "radius", "diameter", "fill"}


def dump(node, indent=0):
    tab = "\t" * indent
    if not isinstance(node, list):
        return tab + _atom(node)
    head = node[0] if node else ""
    simple = all(not isinstance(x, list) for x in node)
    if simple or (head in _INLINE and all(
            not isinstance(x, list) or all(not isinstance(y, list) for y in x) for x in node)):
        parts = []
        for x in node:
            if isinstance(x, list):
                parts.append("(" + " ".join(_atom(y) for y in x) + ")")
            else:
                parts.append(_atom(x))
        return tab + "(" + " ".join(parts) + ")"
    out = [tab + "(" + " ".join(_atom(x) for x in _leading_atoms(node))]
    for x in node[len(_leading_atoms(node)):]:
        out.append(dump(x, indent + 1))
    out.append(tab + ")")
    return "\n".join(out)


def _leading_atoms(node):
    res = []
    for x in node:
        if isinstance(x, list):
            break
        res.append(x)
    return res


def find(node, head):
    """First direct child list whose head is `head`."""
    for x in node:
        if isinstance(x, list) and x and x[0] == head:
            return x
    return None


def find_all(node, head):
    return [x for x in node if isinstance(x, list) and x and x[0] == head]
