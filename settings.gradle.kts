rootProject.name = "CCL1"
include(
    "CCL",
    "HMCLCore",
    "HMCLBoot",
    "HMCLTransformerDiscoveryService"
)

val minecraftLibraries = listOf("HMCLTransformerDiscoveryService")

for (library in minecraftLibraries) {
    project(":$library").projectDir = file("minecraft/libraries/$library")
}
