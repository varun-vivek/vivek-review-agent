# rag_persistent_json_ollama.py
from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
import uvicorn, uuid, io, requests, json, os
from typing import List, Dict, Any

# Text extraction
from tika import parser
from docx import Document

# Embeddings + FAISS
from sentence_transformers import SentenceTransformer
import faiss
import numpy as np

# Tokenizer
import tiktoken

# --- Config ---
OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "llama3.1:8b"
EMBED_MODEL = "all-MiniLM-L6-v2"
TOP_K = 5
CHUNK_TOKENS = 500
CHUNK_OVERLAP = 50

# Persistence files
INDEX_FILE = "faiss_index.bin"
META_FILE = "metadata.json"

# --- Globals ---
app = FastAPI()
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)

docs_meta: Dict[str, Dict[str, Any]] = {}
chunk_ids: List[str] = []
chunk_texts: List[str] = []
index = None
embed_model = SentenceTransformer(EMBED_MODEL)

# --- Helpers ---
def save_state():
    """Persist FAISS index + metadata to disk."""
    global index, docs_meta, chunk_ids, chunk_texts
    if index:
        faiss.write_index(index, INDEX_FILE)
    with open(META_FILE, "w", encoding="utf-8") as f:
        json.dump(
            {"docs_meta": docs_meta, "chunk_ids": chunk_ids, "chunk_texts": chunk_texts},
            f,
            ensure_ascii=False,
        )

def load_state():
    """Load FAISS index + metadata from disk (if exists)."""
    global index, docs_meta, chunk_ids, chunk_texts
    if os.path.exists(INDEX_FILE):
        index = faiss.read_index(INDEX_FILE)
    if os.path.exists(META_FILE):
        with open(META_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            docs_meta = data.get("docs_meta", {})
            chunk_ids = data.get("chunk_ids", [])
            chunk_texts = data.get("chunk_texts", [])

def extract_text(file_bytes: bytes, filename: str) -> str:
    name = filename.lower()
    if name.endswith(".pdf"):
        return parser.from_buffer(file_bytes).get("content", "") or ""
    elif name.endswith(".docx"):
        f = io.BytesIO(file_bytes)
        return "\n".join([p.text for p in Document(f).paragraphs])
    else:
        try:
            return file_bytes.decode("utf-8")
        except:
            return ""

def chunk_text(text: str, model_name="gpt-3.5-turbo", max_tokens=CHUNK_TOKENS, overlap=CHUNK_OVERLAP):
    """Chunk text by token count using tiktoken."""
    # mergeable_ranks = load_tiktoken_bpe(
    #     "https://openaipublic.blob.core.windows.net/encodings/cl100k_base.tiktoken",
    #     expected_hash="223921b76ee99bde995b7ff738513eef100fb51d18c93597a113bcffe865b2a7",
    # )
    # special_tokens = {
    #     ENDOFTEXT: 100257,
    #     FIM_PREFIX: 100258,
    #     FIM_MIDDLE: 100259,
    #     FIM_SUFFIX: 100260,
    #     ENDOFPROMPT: 100276,
    # }
    # return {
    #     "name": "cl100k_base",
    #     "pat_str": r"""'(?i:[sdmt]|ll|ve|re)|[^\r\n\p{L}\p{N}]?+\p{L}++|\p{N}{1,3}+| ?[^\s\p{L}\p{N}]++[\r\n]*+|\s++$|\s*[\r\n]|\s+(?!\S)|\s""",
    #     "mergeable_ranks": mergeable_ranks,
    #     "special_tokens": special_tokens,
    # }
    enc = tiktoken.encoding_for_model(model_name)
    tokens = enc.encode(text)
    chunks, i = [], 0
    while i < len(tokens):
        chunk = tokens[i : i + max_tokens]
        chunks.append(enc.decode(chunk))
        i += max_tokens - overlap
    return chunks

def ensure_index():
    global index
    if index is None:
        dim = embed_model.get_sentence_embedding_dimension()
        index = faiss.IndexFlatIP(dim)
    return index

def add_to_index(new_texts: List[str], filename: str):
    global chunk_texts, chunk_ids, docs_meta
    embeddings = embed_model.encode(new_texts, convert_to_numpy=True)
    faiss.normalize_L2(embeddings)

    idx = ensure_index()
    idx.add(embeddings)

    for t in new_texts:
        cid = str(uuid.uuid4())
        chunk_ids.append(cid)
        chunk_texts.append(t)
        docs_meta[cid] = {"text": t, "source": filename}
    save_state()

def search_chunks(query: str, top_k=TOP_K):
    q_emb = embed_model.encode([query], convert_to_numpy=True)
    faiss.normalize_L2(q_emb)
    D, I = index.search(q_emb, top_k)
    results, sources = [], []
    for idx in I[0]:
        cid = chunk_ids[idx]
        results.append(docs_meta[cid]["text"])
        sources.append(docs_meta[cid]["source"])
    return results, list(dict.fromkeys(sources))

# --- API ---
@app.on_event("startup")
def startup_event():
    load_state()

@app.post("/upload")
async def upload(files: List[UploadFile] = File(...)):
    count = 0
    for f in files:
        text = extract_text(await f.read(), f.filename)
        if not text.strip():
            continue
        chunks = chunk_text(text)
        add_to_index(chunks, f.filename)
        count += len(chunks)
    return {"indexed_chunks": count}

@app.post("/ask")
async def ask(question: str = Form(...)):
    if index is None or len(chunk_ids) == 0:
        return JSONResponse({"error": "No documents uploaded yet"}, status_code=400)

    retrieved, sources = search_chunks(question)
    context = "\n\n---\n\n".join(retrieved)

    # Structured JSON output
    prompt = f"""
You are a helpful assistant. Use ONLY the context provided below.
If not found, say "I don't know".

Return JSON strictly in this schema:
{{
  "answer": "string"
}}

CONTEXT:
{context}

QUESTION:
{question}
"""

    try:
        r = requests.post(
            OLLAMA_URL,
            json={
                "model": OLLAMA_MODEL,
                "prompt": prompt,
                "format": "json",  # enforce structured JSON output
                "stream": False,
            },
            timeout=120,
        )
        r.raise_for_status()
        reply = r.json()
    except Exception as e:
        return JSONResponse({"error": f"Ollama call failed: {e}"}, status_code=500)

    return reply

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)

