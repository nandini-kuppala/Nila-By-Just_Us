#!/usr/bin/env bash
# The parts of DDI-Bench the competition bundle left out: drug names, SMILES,
# what the 86 relation ids mean, and the interaction graph itself.
#
#   Shen et al. (2025), "Benchmarking drug-drug interaction prediction methods:
#   a perspective of distribution changes", Bioinformatics.
#   https://github.com/LARS-research/DDI-Bench
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p ref
BASE=https://raw.githubusercontent.com/LARS-research/DDI-Bench/main/DDI_Ben

fetch() { curl -sSL --max-time 300 "$BASE/$1" -o "ref/$(basename "$1")"; }

# names, so a medicine on a strip can become a node id
fetch DDI-GPT/data/drugbank_random/drug_DDI_GPT.json
# what each relation id says, in DrugBank's own wording
fetch EmerGNN/DrugBank/data/relation2id.json
# DrugBank ids and SMILES, for provenance
fetch EmerGNN/DrugBank/data/node2id.json
fetch DDI_Ben/data/initial/drugbank/id2smiles.json
# the benchmark's own Morgan features, which cover every drug
fetch DDI_Ben/data/initial/drugbank/DB_molecular_feats.pkl
# the interaction graph, split the way the paper splits it
for f in train test_S0 test_S1 test_S2 valid_S0 valid_S1 valid_S2; do
  fetch "DDI_Ben/data/drugbank_random/$f.txt"
done

echo "fetched $(ls ref | wc -l | tr -d ' ') files into ref/"
