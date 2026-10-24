---
change: the-row-keeps-what-the-document-does-not-say
verdict: pass
date: 2026-10-29
pr: 1048
reviewed: a0391cc210e0cd1c8da286b67956e6a701791758
documents: sha256:aeeb5e88e1a9571c768951079b836ba1cadefe8970ba625e8acd0ee04c5ce770
---

# The row keeps what the document does not say

## The change

A document that arrives from another device is applied against the row that is
already on this device: the document wins wherever it carries a value, and a
field the document does not carry is not reset by the arrival. An absent key
is not a claim that the field is empty, and a null value is a claim.

## The review

Eight rounds, by a separate agent that was given the head and the documents
rather than the history of writing them. Round seven found false sentences in
the ADR (a rule that said the merge was per field, and code that said the
other); round eight passed.

## Why this verdict is re-issued

The contract of this change moved after the verdict was written, and a verdict
that speaks for a contract that is not there any more speaks for nothing.

What moved is one link. The change
`the-row-keeps-what-the-document-does-not-say-for-every-type` renamed
`TaskDocumentMerge.kt` to `DocumentMerge.kt` and `TaskDocumentMergeTest.kt` to
`DocumentMergeTest.kt`, and the link in this change's proposal named the old
names. That is a pointer, not a claim: the rule this change states — the
document wins where it speaks, and where it says nothing the row keeps saying
what it says — is unchanged, and the change that generalises it says so in its
own ADR.

The `reviewed:` field names the commit the re-issuing review read — the head
of the generalisation change, `a0391cc2` — and not `78f720a3`, which the eight
rounds were made against: the eight rounds judged the rule, and this re-issue
is about the link that the generalisation moved.

The branch was rebased onto a newer `main` after the reviews, so the commit
the reviews read has the same content and a new name, and the decision that
carries the rule was renamed with it — a rename that moves it into the end of
the corpus of decisions, because the corpus is measured in the order of the
names.

## The eight rounds, in short

1. The rule was stated, and the implementation did not match it.
2. The rule was applied to the task only, and the code said it was about
   tasks.
3. The parts that live outside the row were read as claims.
4. The document of another profile was read against this profile's row.
5. The KDoc contradicted the code about the account the writer reads.
6. The links of this change pointed at files that were renamed.
7. The ADR carried sentences the code did not support.
8. The ADR was rewritten until every sentence in it was true.

## What the verdict does not cover

The task's path is what this verdict is about. The rule was generalised to
every synced type but the project by the change
`the-row-keeps-what-the-document-does-not-say-for-every-type`, and that change
has its own verdict.
