---
name: apm-search
description: Search the local APM photo index by captions, tags, structured facets, visible text, and recognized person or pet names. Use when a user asks an Agent to find, filter, or combine conditions for indexed photos without changing the catalog or original images.
---

# APM Search

Use the bundled script as the only photo tool. It opens an existing APM SQLite index read-only, runs one to four atomic queries, and returns JSON.

## Search workflow

1. Rewrite the request as one to four short terms that can occur in annotation metadata. Keep exact configured names, object counts, actions, scenes, daylight, or visible text.
2. Choose `all` when every term must match the same photo; choose `any` when any term is sufficient.
3. Run:

```bash
python3 skills/apm-search/scripts/search.py \
  --query "天黑" \
  --query "旺财" \
  --match all
```

Pass `--db /absolute/path/to/apm.sqlite3` when the index is not at `APM_DB` or `~/.apm/apm.sqlite3`. Pass `--limit N` to cap each atomic query.

4. Read `invocations` to report which searches ran. Use `results` as the evidence; do not claim a match that the script did not return.

## Boundaries

- Never initialize, scan, tag, move, delete, or edit from this skill.
- Never read image bytes. Search only generated metadata in an existing index.
- Do not invent recognized names. Search an exact person or pet name only when the user supplied it or it appears in existing context.
- If the database is absent, has no annotations, or yields no result, report that state and suggest scanning and annotation in APM.
