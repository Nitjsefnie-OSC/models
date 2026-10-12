// Diagnostic external oracle executable. Never linked or shipped by Models.
#include "llama.h"
#include "ggml-backend.h"
#include "nlohmann/json.hpp"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>
#include <algorithm>
using json = nlohmann::ordered_json;
namespace fs = std::filesystem;
struct capture_state {fs::path folder; int sequence=0; bool enabled=false;};
static bool capture(ggml_tensor *t,bool ask,void *opaque) {
    auto &state=*static_cast<capture_state *>(opaque);
    std::string name=t->name;
    bool selected=state.enabled && t->type==GGML_TYPE_F32 &&
      (name.find("-0")!=std::string::npos || name=="model.input_embed");
    if(ask)return selected;
    if(!selected)return true;
    std::vector<uint8_t> data(ggml_nbytes(t));ggml_backend_tensor_get(t,data.data(),0,data.size());
    std::string safe=name;for(auto &c:safe)if(!isalnum(c)&&c!='.'&&c!='-'&&c!='_')c='_';
    std::string file=std::to_string(state.sequence++)+"-"+safe+".bin";
    std::ofstream binary(state.folder/file,std::ios::binary);binary.write(reinterpret_cast<char *>(data.data()),data.size());
    json row={{"name",name},{"file",file},{"bytes",data.size()},{"op",ggml_op_name(t->op)},
      {"ne",std::vector<int64_t>(t->ne,t->ne+4)},{"nb",std::vector<size_t>(t->nb,t->nb+4)}};
    for(int i=0;i<2;i++)if(t->src[i])row["src"+std::to_string(i)]=t->src[i]->name;
    std::ofstream(state.folder/"tensors.jsonl",std::ios::app)<<row.dump()<<"\n";return true;
}
int main(int argc,char **argv) {
    if(argc!=5 && argc!=6){std::cerr<<"model requests out microbatch [repack=0|1]\n";return 2;}
    llama_backend_init();
    auto mp=llama_model_default_params();mp.n_gpu_layers=0;
    if(argc==6){std::string option=argv[5];if(option!="0" && option!="1")return 2;mp.use_extra_bufts=option=="1";}
    auto *model=llama_model_load_from_file(argv[1],mp);if(!model)return 3;
    capture_state state;
    auto cp=llama_context_default_params();cp.n_ctx=2048;cp.n_batch=2048;cp.n_ubatch=std::stoi(argv[4]);
    cp.n_threads=4;cp.n_threads_batch=4;cp.n_seq_max=1;cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.type_k=GGML_TYPE_F32;cp.type_v=GGML_TYPE_F32;cp.offload_kqv=false;cp.op_offload=false;
    cp.cb_eval=capture;cp.cb_eval_user_data=&state;
    auto *ctx=llama_init_from_model(model,cp);if(!ctx)return 4;
    json tasks;std::ifstream(argv[2])>>tasks;
    for(auto &task:tasks) {
        auto id=task["id"].get<std::string>();state.folder=fs::path(argv[3])/id;fs::create_directories(state.folder);
        state.sequence=0;llama_memory_clear(llama_get_memory(ctx),true);
        auto tokens=task["inputTokens"].get<std::vector<llama_token>>();
        state.enabled=id=="easy-intent-00";
        if(llama_decode(ctx,llama_batch_get_one(tokens.data(),tokens.size())))return 5;
        state.enabled=false;std::vector<llama_token> generated;
        int count=llama_vocab_n_tokens(llama_model_get_vocab(model));
        for(int step=0;step<8;step++) {
            float *logits=llama_get_logits_ith(ctx,-1);
            std::ofstream out(state.folder/("logits-"+std::to_string(step)+".f32"),std::ios::binary);
            out.write(reinterpret_cast<char *>(logits),count*sizeof(float));
            auto token=static_cast<llama_token>(std::max_element(logits,logits+count)-logits);generated.push_back(token);
            if(step<7 && llama_decode(ctx,llama_batch_get_one(&token,1)))return 6;
        }
        json result={{"id",id},{"inputTokens",tokens},{"tokens",generated}};
        std::ofstream(state.folder/"result.json")<<result.dump()<<"\n";std::cout<<result.dump()<<std::endl;
    }
    llama_free(ctx);llama_model_free(model);llama_backend_free();return 0;
}
