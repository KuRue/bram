"""Make deterministic, non-language-model weights for native protocol tests only.

Requires numpy and gguf-py from Bram's pinned llama.cpp checkout. The generated model always
prefers the byte token A: it tests loading, token replay, and threadpool behavior, not model quality.
"""
import argparse
import sys
from pathlib import Path
import numpy as np
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--gguf-python', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.gguf_python))
import gguf
path = str(args.output)
args.output.parent.mkdir(parents=True, exist_ok=True)
w = gguf.GGUFWriter(path, 'llama')
w.add_name('Bram procedural native validation fixture')
w.add_context_length(512)
w.add_embedding_length(128)
w.add_block_count(1)
w.add_feed_forward_length(256)
w.add_head_count(4)
w.add_head_count_kv(4)
w.add_rope_dimension_count(32)
w.add_layer_norm_rms_eps(1e-5)
w.add_file_type(gguf.LlamaFileType.ALL_F32)
w.add_tokenizer_model('llama')
tokens=['<unk>','<s>','</s>']+[f'<0x{i:02X}>' for i in range(256)]+['▁']
w.add_token_list(tokens)
w.add_token_scores([0.0]*len(tokens))
w.add_token_types([2,3,3]+[6]*256+[1])
w.add_bos_token_id(1)
w.add_eos_token_id(2)
w.add_unk_token_id(0)
w.add_bool('tokenizer.ggml.add_bos_token', True)
w.add_bool('tokenizer.ggml.add_eos_token', False)
w.add_chat_template("{% for message in messages %}{{ message['role'] + ': ' + message['content'] + '\\n' }}{% endfor %}{% if add_generation_prompt %}{{ 'assistant: ' }}{% endif %}")
rng=np.random.default_rng(20261018)
w.add_tensor('token_embd.weight', rng.uniform(.01,.02,(len(tokens),128)).astype(np.float32))
w.add_tensor('output_norm.weight', np.ones(128, dtype=np.float32))
out=np.zeros((len(tokens),128),dtype=np.float32);out[68,:]=1
w.add_tensor('output.weight',out)
for name,shape in [('attn_norm.weight',(128,)),('attn_q.weight',(128,128)),('attn_k.weight',(128,128)),('attn_v.weight',(128,128)),('attn_output.weight',(128,128)),('ffn_norm.weight',(128,)),('ffn_gate.weight',(256,128)),('ffn_up.weight',(256,128)),('ffn_down.weight',(128,256))]:
 value=np.ones(shape,dtype=np.float32) if len(shape)==1 else rng.normal(0,.001,shape).astype(np.float32)
 w.add_tensor('blk.0.'+name,value)
w.write_header_to_file();w.write_kv_data_to_file();w.write_tensors_to_file();w.close()
print(path,Path(path).stat().st_size)
