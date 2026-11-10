#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

// «Рыбий глаз»: центр кадра увеличен, края сжаты (бочкообразное искажение), по краям лёгкое затемнение.
const float K = 0.35;

void main() {
    vec2 p = texCoord * 2.0 - 1.0;
    float r2 = dot(p, p);
    vec2 q = p * (1.0 + K * r2) / (1.0 + 2.0 * K);
    vec4 color = texture(InSampler, q * 0.5 + 0.5);
    fragColor = vec4(color.rgb * (1.0 - 0.3 * smoothstep(0.6, 2.0, r2)), 1.0);
}
