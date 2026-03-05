import math
import re
from collections import Counter


_TOKEN_RE = re.compile(r"[A-Za-z0-9]+|[\u4e00-\u9fff]")


def _tokenize(text: str) -> list[str]:
    return [token.lower() for token in _TOKEN_RE.findall(text)]


def _normalize_score_map(scores: dict[str, float]) -> dict[str, float]:
    if not scores:
        return {}
    values = list(scores.values())
    min_v = min(values)
    max_v = max(values)
    if abs(max_v - min_v) < 1e-9:
        return {key: 1.0 for key in scores}
    return {key: (value - min_v) / (max_v - min_v) for key, value in scores.items()}


def _idf(corpus_tokens: list[list[str]]) -> dict[str, float]:
    if not corpus_tokens:
        return {}
    doc_count = len(corpus_tokens)
    df: Counter[str] = Counter()
    for tokens in corpus_tokens:
        df.update(set(tokens))
    idf: dict[str, float] = {}
    for term, freq in df.items():
        idf[term] = math.log((doc_count - freq + 0.5) / (freq + 0.5) + 1.0)
    return idf


def _bm25_score(
    query_tokens: list[str],
    doc_tokens: list[str],
    idf: dict[str, float],
    avg_dl: float,
    k1: float = 1.5,
    b: float = 0.75,
) -> float:
    if not doc_tokens:
        return 0.0
    doc_freq = Counter(doc_tokens)
    dl = len(doc_tokens)
    score = 0.0
    for token in query_tokens:
        tf = doc_freq.get(token, 0)
        if tf == 0:
            continue
        token_idf = idf.get(token, 0.0)
        num = tf * (k1 + 1.0)
        den = tf + k1 * (1.0 - b + b * (dl / max(avg_dl, 1.0)))
        score += token_idf * (num / max(den, 1e-9))
    return score


def _tfidf_vector(tokens: list[str], idf: dict[str, float]) -> dict[str, float]:
    if not tokens:
        return {}
    tf = Counter(tokens)
    total = len(tokens)
    vector: dict[str, float] = {}
    for token, count in tf.items():
        vector[token] = (count / total) * idf.get(token, 0.0)
    return vector


def _cosine_sim(left: dict[str, float], right: dict[str, float]) -> float:
    if not left or not right:
        return 0.0
    common = set(left.keys()) & set(right.keys())
    dot = sum(left[key] * right[key] for key in common)
    left_norm = math.sqrt(sum(value * value for value in left.values()))
    right_norm = math.sqrt(sum(value * value for value in right.values()))
    if left_norm == 0.0 or right_norm == 0.0:
        return 0.0
    return dot / (left_norm * right_norm)


def lexical_search(chunks: list[dict], query: str, top_k: int) -> list[dict]:
    query_tokens = _tokenize(query)
    if not query_tokens:
        return []
    query_set = set(query_tokens)
    ranked: list[tuple[float, dict]] = []
    for chunk in chunks:
        content = chunk.get("content", "")
        content_tokens = _tokenize(content)
        if not content_tokens:
            continue
        overlap = len(query_set & set(content_tokens))
        if overlap == 0:
            continue
        score = overlap / max(len(query_set), 1)
        ranked.append((score, chunk))
    ranked.sort(key=lambda item: item[0], reverse=True)
    items: list[dict] = []
    for score, chunk in ranked[:top_k]:
        items.append(
            {
                "score": round(score, 4),
                "bm25_score": 0.0,
                "dense_score": 0.0,
                "retrieval_score": round(score, 4),
                "rerank_score": None,
                "chunk_id": chunk["chunk_id"],
                "document_id": chunk["document_id"],
                "chunk_index": chunk["chunk_index"],
                "content": chunk["content"],
            }
        )
    return items


def search_chunks(
    chunks: list[dict],
    query: str,
    top_k: int,
    search_mode: str = "hybrid",
    candidate_k: int = 20,
    rerank: bool = True,
    bm25_weight: float = 0.6,
    dense_weight: float = 0.4,
) -> dict:
    if top_k <= 0:
        return {"strategy": search_mode, "rerank_applied": False, "retrieved_candidates": 0, "items": []}

    query_tokens = _tokenize(query)
    if not query_tokens:
        return {"strategy": search_mode, "rerank_applied": False, "retrieved_candidates": 0, "items": []}

    prepared: list[dict] = []
    corpus_tokens: list[list[str]] = []
    for chunk in chunks:
        content = chunk.get("content", "")
        tokens = _tokenize(content)
        if not tokens:
            continue
        prepared.append(chunk)
        corpus_tokens.append(tokens)
    if not prepared:
        return {"strategy": search_mode, "rerank_applied": False, "retrieved_candidates": 0, "items": []}

    idf = _idf(corpus_tokens)
    avg_dl = sum(len(tokens) for tokens in corpus_tokens) / max(len(corpus_tokens), 1)

    query_vector = _tfidf_vector(query_tokens, idf)
    bm25_raw: dict[str, float] = {}
    dense_raw: dict[str, float] = {}

    for index, chunk in enumerate(prepared):
        chunk_id = chunk["chunk_id"]
        tokens = corpus_tokens[index]
        bm25_raw[chunk_id] = _bm25_score(query_tokens, tokens, idf, avg_dl=avg_dl)
        dense_raw[chunk_id] = _cosine_sim(query_vector, _tfidf_vector(tokens, idf))

    bm25_norm = _normalize_score_map(bm25_raw)
    dense_norm = _normalize_score_map(dense_raw)

    candidate_count = min(max(candidate_k, top_k), len(prepared))
    q_compact = "".join(query_tokens)
    q_set = set(query_tokens)

    mixed: list[dict] = []
    for chunk in prepared:
        chunk_id = chunk["chunk_id"]
        if search_mode == "bm25":
            retrieval_score = bm25_norm.get(chunk_id, 0.0)
        elif search_mode == "dense":
            retrieval_score = dense_norm.get(chunk_id, 0.0)
        else:
            weight_sum = max(bm25_weight + dense_weight, 1e-9)
            retrieval_score = (
                bm25_weight * bm25_norm.get(chunk_id, 0.0) + dense_weight * dense_norm.get(chunk_id, 0.0)
            ) / weight_sum
        mixed.append(
            {
                "score": round(retrieval_score, 4),
                "bm25_score": round(bm25_raw.get(chunk_id, 0.0), 4),
                "dense_score": round(dense_raw.get(chunk_id, 0.0), 4),
                "retrieval_score": round(retrieval_score, 4),
                "rerank_score": None,
                "chunk_id": chunk_id,
                "document_id": chunk["document_id"],
                "chunk_index": chunk["chunk_index"],
                "content": chunk["content"],
            }
        )

    mixed.sort(key=lambda item: item["retrieval_score"], reverse=True)
    candidates = mixed[:candidate_count]

    rerank_applied = bool(rerank and search_mode == "hybrid")
    if rerank_applied:
        for item in candidates:
            content_tokens = _tokenize(item["content"])
            if not content_tokens:
                continue
            overlap = len(q_set & set(content_tokens)) / max(len(q_set), 1)
            phrase_bonus = 0.15 if q_compact in "".join(content_tokens) else 0.0
            early_window = "".join(content_tokens[:40])
            early_bonus = 0.05 if q_compact in early_window else 0.0
            rerank_score = 0.65 * item["retrieval_score"] + 0.25 * overlap + phrase_bonus + early_bonus
            item["rerank_score"] = round(rerank_score, 4)
            item["score"] = item["rerank_score"]
        candidates.sort(key=lambda item: item["rerank_score"] or 0.0, reverse=True)

    final_items = candidates[:top_k]
    return {
        "strategy": search_mode,
        "rerank_applied": rerank_applied,
        "retrieved_candidates": len(candidates),
        "items": final_items,
    }
