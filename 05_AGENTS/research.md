# Research Agent

The `ResearchAgent` coordinates external information gathering, source evaluation, evidence extraction, synthesis, and conflict detection across multiple sources.

---

## Responsibilities & Flow

```
User Request → ResearchAgent → WebSearchClient → ResearchSource Retrieval → Source Evaluation → Evidence Extraction → Synthesis → Conflict Detection → ResearchResult
```

1. **Source Retrieval**: Gathers references and documents from decoupled web search clients.
2. **Evaluation & Credibility**: Computes relevance and reliability scores.
3. **Evidence Extraction**: Extracts structured claims and excerpts with source provenance.
4. **Conflict Detection**: Flags contested claims when sources disagree.
5. **Knowledge Persistence**: Optionally saves synthesized research to Obsidian knowledge base.
