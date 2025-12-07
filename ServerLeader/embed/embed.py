import sys, json, base64
from sentence_transformers import SentenceTransformer

# Load the model
model = SentenceTransformer('all-MiniLM-L6-v2')

path = sys.argv[1]
try:
    with open(path, 'rb') as f:
        data = f.read()
    try:
        content = data.decode('utf-8')
    except:
        content = base64.b64encode(data[:4096]).decode('utf-8')
except Exception as e:
    content = path  # fallback

# Generate embedding
embedding = model.encode([content])[0]
print(json.dumps(embedding.tolist()))
