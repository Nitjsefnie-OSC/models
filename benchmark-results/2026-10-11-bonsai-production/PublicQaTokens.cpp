// Tokenization-only oracle, compiled against the pinned publisher fork. No inference.
#include "llama.h"
#include "nlohmann/json.hpp"
#include <fstream>
#include <iostream>
#include <vector>

int main(int argc, char **argv) {
    if (argc != 4) return 2;
    llama_backend_init();
    auto params = llama_model_default_params();
    params.vocab_only = true;
    params.n_gpu_layers = 0;
    auto *model = llama_model_load_from_file(argv[1], params);
    if (!model) return 3;
    const auto *vocab = llama_model_get_vocab(model);
    std::ifstream input(argv[2]);
    nlohmann::json manifest;
    input >> manifest;
    std::ofstream output(argv[3]);
    int index = 0;
    for (const auto &item : manifest.at("cases")) {
        std::string prompt = item.at("prompt");
        int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        std::vector<llama_token> tokens(count);
        int actual = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true);
        if (actual != count) return 4;
        output << nlohmann::json{{"index", index++}, {"id", item.at("id")},
            {"promptSha256", item.at("promptSha256")}, {"inputTokenIds", tokens}}.dump() << '\n';
    }
    llama_model_free(model);
    llama_backend_free();
    return output.good() ? 0 : 5;
}
