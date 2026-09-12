"""The trained model's architecture, copied verbatim so the weights will load.

This is the network definition from the DDI-Bench training script, and nothing
else from it: no training loop, no loss, no data pipeline. It exists because a
checkpoint is only weights, and `load_state_dict` needs the shapes to line up
exactly. Editing anything here will silently change what the checkpoint means,
so don't -- `export_onnx.py` checks the export against PyTorch, and
`evaluate.py` checks that against the score the training run recorded.

Substructure cross-attention over a pair of Morgan fingerprints: each drug's
1,024 counts are cut into 64 blocks of 16, each block becomes a token, the two
drugs attend to each other in both directions, and a dense pathway carries the
exact bit overlap alongside. 4.9M parameters, 86 outputs.
"""
import torch
import torch.nn as nn
import torch.nn.functional as F

N_CLASSES = 86
FP_HALF = 1024
N_BLOCKS = 64
BLOCK_SIZE = 16
N_DRUGS = 1710


class PreNormSelfAttentionBlock(nn.Module):
    def __init__(self, d_model: int, n_heads: int, dropout: float):
        super().__init__()
        self.norm_attn = nn.LayerNorm(d_model)
        self.attn = nn.MultiheadAttention(
            embed_dim=d_model,
            num_heads=n_heads,
            dropout=dropout,
            batch_first=True,
        )
        self.norm_ffn = nn.LayerNorm(d_model)
        self.ffn = nn.Sequential(
            nn.Linear(d_model, 4 * d_model),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(4 * d_model, d_model),
        )
        self.resid_dropout = nn.Dropout(dropout)

    def forward(self, x):
        h = self.norm_attn(x)
        h, _ = self.attn(h, h, h, need_weights=False)
        x = x + self.resid_dropout(h)
        x = x + self.resid_dropout(self.ffn(self.norm_ffn(x)))
        return x


class CrossAttentionBlock(nn.Module):
    """Role-preserving attention from one drug's tokens toward the other drug."""

    def __init__(self, d_model: int, n_heads: int, dropout: float):
        super().__init__()
        self.norm_query = nn.LayerNorm(d_model)
        self.norm_context = nn.LayerNorm(d_model)
        self.attn = nn.MultiheadAttention(
            embed_dim=d_model,
            num_heads=n_heads,
            dropout=dropout,
            batch_first=True,
        )
        self.norm_ffn = nn.LayerNorm(d_model)
        self.ffn = nn.Sequential(
            nn.Linear(d_model, 4 * d_model),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(4 * d_model, d_model),
        )
        self.resid_dropout = nn.Dropout(dropout)

    def forward(self, query_tokens, context_tokens):
        q = self.norm_query(query_tokens)
        kv = self.norm_context(context_tokens)
        context, _ = self.attn(q, kv, kv, need_weights=False)
        query_tokens = query_tokens + self.resid_dropout(context)
        query_tokens = query_tokens + self.resid_dropout(
            self.ffn(self.norm_ffn(query_tokens))
        )
        return query_tokens


class LearnedAttentionPooling(nn.Module):
    """A learned query selects chemically relevant block tokens."""

    def __init__(self, d_model: int):
        super().__init__()
        self.key_projection = nn.Linear(d_model, d_model, bias=False)
        self.query = nn.Parameter(torch.zeros(d_model))
        nn.init.normal_(self.query, mean=0.0, std=0.02)
        self.scale = float(d_model) ** -0.5

    def forward(self, tokens):
        keys = self.key_projection(tokens)
        attention = torch.softmax(
            torch.einsum("bnd,d->bn", keys, self.query) * self.scale,
            dim=1,
        )
        return torch.einsum("bn,bnd->bd", attention, tokens)


class DrugBlockEncoder(nn.Module):
    """
    Shared encoder for either drug-role.

    Each token contains the IDF-weighted 16-bit hashed block and the local
    popcount density. The shared representation preserves the chemical meaning
    of a block independently of whether the drug appears first or second.
    """

    def __init__(
        self,
        n_blocks: int,
        block_size: int,
        d_model: int,
        n_heads: int,
        n_layers: int,
        dropout: float,
    ):
        super().__init__()
        token_dim = block_size + 1  # weighted bits + normalized block popcount
        self.token_projection = nn.Sequential(
            nn.Linear(token_dim, d_model),
            nn.LayerNorm(d_model),
            nn.GELU(),
            nn.Dropout(dropout),
        )
        self.pos_embedding = nn.Parameter(torch.randn(1, n_blocks, d_model) * 0.02)
        self.layers = nn.ModuleList(
            [
                PreNormSelfAttentionBlock(d_model, n_heads, dropout)
                for _ in range(n_layers)
            ]
        )
        self.output_norm = nn.LayerNorm(d_model)

    def forward(self, weighted_bits, block_density):
        # weighted_bits:    [B, N_BLOCKS, BLOCK_SIZE]
        # block_density:    [B, N_BLOCKS, 1]
        h = torch.cat([weighted_bits, block_density], dim=-1)
        h = self.token_projection(h) + self.pos_embedding
        for layer in self.layers:
            h = layer(h)
        return self.output_norm(h)


class SubstructureCrossAttentionDDIClassifier(nn.Module):
    """
    One jointly trained model that combines:

    1. Role-aware substructure-set encoding for each drug.
    2. Directional cross-attention for drug1 -> drug2 and drug2 -> drug1
       interactions.
    3. An exact same-bit overlap shortcut derived directly from raw Morgan bits.
    4. Bounded identity embeddings for drugs seen during training. All
       embeddings start at zero, so unseen S1/S2 drugs receive a neutral
       identity representation rather than random noise.
    """

    def __init__(
        self,
        n_classes: int = 86,
        fp_half: int = 1024,
        n_blocks: int = 64,
        block_size: int = 16,
        n_drugs: int = 1710,
        d_model: int = 192,
        n_heads: int = 8,
        self_attention_layers: int = 1,
        dense_dim: int = 256,
        id_dim: int = 64,
        id_hidden: int = 128,
        attention_dropout: float = 0.12,
        dense_dropout: float = 0.15,
        id_dropout: float = 0.15,
        head_dropout: float = 0.25,
    ):
        super().__init__()
        self.n_classes = n_classes
        self.fp_half = fp_half
        self.n_blocks = n_blocks
        self.block_size = block_size
        self.n_drugs = n_drugs

        self.drug_encoder = DrugBlockEncoder(
            n_blocks=n_blocks,
            block_size=block_size,
            d_model=d_model,
            n_heads=n_heads,
            n_layers=self_attention_layers,
            dropout=attention_dropout,
        )

        # Separate direction weights retain the semantics of ordered pairs.
        self.cross_1_to_2 = CrossAttentionBlock(d_model, n_heads, attention_dropout)
        self.cross_2_to_1 = CrossAttentionBlock(d_model, n_heads, attention_dropout)

        self.pool_base_1 = LearnedAttentionPooling(d_model)
        self.pool_base_2 = LearnedAttentionPooling(d_model)
        self.pool_cross_1 = LearnedAttentionPooling(d_model)
        self.pool_cross_2 = LearnedAttentionPooling(d_model)

        # Weighted pair bits, exact overlap, global block sketches, and scalar
        # density features form a compact residual pathway.
        dense_input_dim = (
            2 * fp_half  # IDF-weighted drug1 || drug2 bits
            + fp_half  # exact same-substructure-bit overlap
            + 2 * n_blocks  # block popcount sketches
            + 4  # density statistics
        )
        self.dense_pair_encoder = nn.Sequential(
            nn.Linear(dense_input_dim, 512),
            nn.LayerNorm(512),
            nn.GELU(),
            nn.Dropout(dense_dropout),
            nn.Linear(512, dense_dim),
            nn.LayerNorm(dense_dim),
            nn.GELU(),
            nn.Dropout(dense_dropout),
        )

        # Index 0 is reserved for invalid/out-of-vocabulary identifiers.
        self.drug_embedding = nn.Embedding(n_drugs + 1, id_dim)
        nn.init.zeros_(self.drug_embedding.weight)
        self.identity_projection = nn.Sequential(
            nn.Linear(2 * id_dim, id_hidden),
            nn.LayerNorm(id_hidden),
            nn.GELU(),
            nn.Dropout(id_dropout),
        )

        # Two base drug summaries plus two cross summaries, signed difference,
        # elementwise product, dense residual, and identity context.
        fusion_dim = 6 * d_model + dense_dim + id_hidden
        self.classifier = nn.Sequential(
            nn.LayerNorm(fusion_dim),
            nn.Linear(fusion_dim, 768),
            nn.GELU(),
            nn.Dropout(head_dropout),
            nn.Linear(768, 384),
            nn.GELU(),
            nn.Dropout(head_dropout),
            nn.Linear(384, n_classes),
        )

    @staticmethod
    def _safe_drug_index(drug_ids, n_drugs: int):
        drug_ids = drug_ids.long()
        valid = (drug_ids >= 0) & (drug_ids < n_drugs)
        # Drug index k maps to embedding row k+1; invalid IDs map to zero row.
        return torch.where(valid, drug_ids + 1, torch.zeros_like(drug_ids))

    def forward(
        self,
        x_raw,
        x_weighted,
        block_features,
        density_features,
        drug1_id=None,
        drug2_id=None,
    ):
        x_raw = x_raw.float()
        x_weighted = x_weighted.float()
        block_features = block_features.float()
        density_features = density_features.float()
        batch_size = x_raw.shape[0]
        device = x_raw.device

        fp_tokens = x_weighted.view(batch_size, 2, self.n_blocks, self.block_size)
        block_density = block_features.view(batch_size, 2, self.n_blocks, 1)

        tokens_1 = self.drug_encoder(fp_tokens[:, 0], block_density[:, 0])
        tokens_2 = self.drug_encoder(fp_tokens[:, 1], block_density[:, 1])

        # Directional contexts model asymmetric pharmacological roles.
        contextual_1 = self.cross_1_to_2(tokens_1, tokens_2)
        contextual_2 = self.cross_2_to_1(tokens_2, tokens_1)

        base_1 = self.pool_base_1(tokens_1)
        base_2 = self.pool_base_2(tokens_2)
        cross_1 = self.pool_cross_1(contextual_1)
        cross_2 = self.pool_cross_2(contextual_2)

        interaction_features = torch.cat(
            [
                base_1,
                base_2,
                cross_1,
                cross_2,
                cross_1 - cross_2,
                cross_1 * cross_2,
            ],
            dim=-1,
        )

        # Exact overlap is meaningful because both drugs use the same hashed
        # Morgan-bit coordinate system.
        exact_overlap = x_raw[:, : self.fp_half] * x_raw[:, self.fp_half :]
        dense_input = torch.cat(
            [
                x_weighted,
                exact_overlap,
                block_features,
                density_features,
            ],
            dim=-1,
        )
        dense_features = self.dense_pair_encoder(dense_input)

        if drug1_id is None:
            drug1_id = torch.zeros(batch_size, dtype=torch.long, device=device)
        if drug2_id is None:
            drug2_id = torch.zeros(batch_size, dtype=torch.long, device=device)

        index_1 = self._safe_drug_index(drug1_id, self.n_drugs)
        index_2 = self._safe_drug_index(drug2_id, self.n_drugs)
        identity_features = torch.cat(
            [
                self.drug_embedding(index_1),
                self.drug_embedding(index_2),
            ],
            dim=-1,
        )
        identity_features = self.identity_projection(identity_features)

        fused = torch.cat(
            [interaction_features, dense_features, identity_features],
            dim=-1,
        )
        return self.classifier(fused)


