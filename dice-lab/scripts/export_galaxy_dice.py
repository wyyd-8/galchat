import os
import sys

import bpy


EXPORTS = {
    "D4": "D4_四面骰_星穹_baked.glb",
    "D6": "D6_六面骰_星穹_baked.glb",
    "D8": "D8_八面骰_星穹_baked.glb",
    "D10_0_9": "D10_个位骰_0-9_星穹_baked.glb",
    "D10_00_90": "D10_百分骰_00-90_星穹_baked.glb",
    "D12": "D12_十二面骰_星穹_baked.glb",
    "D20": "D20_二十面骰_星穹_baked.glb",
}


def output_directory():
    separator = sys.argv.index("--")
    arguments = sys.argv[separator + 1:]
    if len(arguments) != 1:
        raise RuntimeError("Expected output directory after --")
    return os.path.abspath(arguments[0])


def select_only(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.hide_set(False)
    obj.hide_viewport = False
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def ensure_uv_map(obj):
    if obj.data.uv_layers:
        return
    select_only(obj)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=1.15192, island_margin=0.03)
    bpy.ops.object.mode_set(mode="OBJECT")


def set_input(node, names, value):
    if isinstance(names, str):
        names = (names,)
    for name in names:
        socket = node.inputs.get(name)
        if socket is not None:
            socket.default_value = value
            return socket
    return None


def gradient_material(name):
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    nodes.clear()
    output = nodes.new("ShaderNodeOutputMaterial")
    principled = nodes.new("ShaderNodeBsdfPrincipled")
    coordinate = nodes.new("ShaderNodeTexCoord")
    direction = nodes.new("ShaderNodeVectorMath")
    direction.operation = "DOT_PRODUCT"
    direction.inputs[1].default_value = (0.22, 0.13, 0.65)
    gradient = nodes.new("ShaderNodeValToRGB")
    ramp = gradient.color_ramp
    ramp.elements.remove(ramp.elements[1])
    stops = [
        (0.00, (0.003, 0.008, 0.055, 1.0)),
        (0.30, (0.005, 0.035, 0.19, 1.0)),
        (0.55, (0.025, 0.09, 0.46, 1.0)),
        (0.76, (0.15, 0.025, 0.50, 1.0)),
        (1.00, (0.42, 0.025, 0.42, 1.0)),
    ]
    ramp.elements[0].position, ramp.elements[0].color = stops[0]
    for position, color in stops[1:]:
        element = ramp.elements.new(position)
        element.color = color
    nebula = nodes.new("ShaderNodeTexNoise")
    nebula.inputs["Scale"].default_value = 2.3
    nebula.inputs["Detail"].default_value = 8.0
    nebula.inputs["Roughness"].default_value = 0.70
    nebula.inputs["Distortion"].default_value = 1.15
    fine_cloud = nodes.new("ShaderNodeTexNoise")
    fine_cloud.inputs["Scale"].default_value = 8.5
    fine_cloud.inputs["Detail"].default_value = 5.0
    fine_cloud.inputs["Roughness"].default_value = 0.68
    fine_cloud.inputs["Distortion"].default_value = 0.55
    cloud_mix = nodes.new("ShaderNodeMixRGB")
    cloud_mix.blend_type = "MULTIPLY"
    cloud_mix.inputs[0].default_value = 0.58
    fog_ramp = nodes.new("ShaderNodeValToRGB")
    fog = fog_ramp.color_ramp
    fog.elements.remove(fog.elements[1])
    fog_stops = [
        (0.00, (0.002, 0.005, 0.035, 1.0)),
        (0.25, (0.004, 0.025, 0.15, 1.0)),
        (0.37, (0.005, 0.20, 0.58, 1.0)),
        (0.45, (0.03, 0.07, 0.42, 1.0)),
        (0.56, (0.20, 0.025, 0.52, 1.0)),
        (0.66, (0.52, 0.035, 0.48, 1.0)),
        (0.77, (0.08, 0.012, 0.30, 1.0)),
        (1.00, (0.002, 0.006, 0.055, 1.0)),
    ]
    fog.elements[0].position, fog.elements[0].color = fog_stops[0]
    for position, color in fog_stops[1:]:
        element = fog.elements.new(position)
        element.color = color
    blend = nodes.new("ShaderNodeMixRGB")
    blend.blend_type = "OVERLAY"
    blend.inputs[0].default_value = 0.46
    star_noise = nodes.new("ShaderNodeTexNoise")
    star_noise.inputs["Scale"].default_value = 58.0
    star_noise.inputs["Detail"].default_value = 1.2
    star_noise.inputs["Roughness"].default_value = 0.32
    star_mask = nodes.new("ShaderNodeValToRGB")
    star_mask.color_ramp.interpolation = "CONSTANT"
    star_mask.color_ramp.elements[0].position = 0.815
    star_mask.color_ramp.elements[0].color = (0, 0, 0, 1)
    star_mask.color_ramp.elements[1].position = 0.825
    star_mask.color_ramp.elements[1].color = (1, 1, 1, 1)
    add_stars = nodes.new("ShaderNodeMixRGB")
    add_stars.blend_type = "ADD"
    add_stars.inputs[2].default_value = (0.72, 0.88, 1.0, 1.0)
    links.new(coordinate.outputs["Generated"], direction.inputs[0])
    links.new(direction.outputs["Value"], gradient.inputs["Fac"])
    links.new(coordinate.outputs["Generated"], nebula.inputs["Vector"])
    links.new(coordinate.outputs["Generated"], fine_cloud.inputs["Vector"])
    links.new(nebula.outputs["Fac"], cloud_mix.inputs[1])
    links.new(fine_cloud.outputs["Fac"], cloud_mix.inputs[2])
    links.new(cloud_mix.outputs["Color"], fog_ramp.inputs["Fac"])
    links.new(gradient.outputs["Color"], blend.inputs[1])
    links.new(fog_ramp.outputs["Color"], blend.inputs[2])
    links.new(coordinate.outputs["Generated"], star_noise.inputs["Vector"])
    links.new(star_noise.outputs["Fac"], star_mask.inputs["Fac"])
    links.new(star_mask.outputs["Color"], add_stars.inputs[0])
    links.new(blend.outputs["Color"], add_stars.inputs[1])
    links.new(add_stars.outputs["Color"], principled.inputs["Base Color"])
    set_input(principled, "Metallic", 0.04)
    set_input(principled, "Roughness", 0.12)
    set_input(principled, ("Transmission Weight", "Transmission"), 0.28)
    set_input(principled, "IOR", 1.47)
    set_input(principled, "Alpha", 0.84)
    set_input(principled, ("Emission Color", "Emission"), (0.025, 0.04, 0.18, 1.0))
    set_input(principled, "Emission Strength", 0.09)
    set_input(principled, ("Coat Weight", "Clearcoat"), 0.42)
    set_input(principled, ("Coat Roughness", "Clearcoat Roughness"), 0.06)
    links.new(principled.outputs["BSDF"], output.inputs["Surface"])
    material.surface_render_method = "BLENDED"
    return material


def bake_body(body, material):
    body.data.materials.clear()
    body.data.materials.append(material)
    ensure_uv_map(body)
    nodes = material.node_tree.nodes
    links = material.node_tree.links
    principled = next(node for node in nodes if node.type == "BSDF_PRINCIPLED")
    image = bpy.data.images.new(
        name=f"{body.name}_GalaxyBaseColor",
        width=1024,
        height=1024,
        alpha=False,
    )
    image.colorspace_settings.name = "sRGB"
    texture = nodes.new("ShaderNodeTexImage")
    texture.name = "Baked Galaxy Base Color"
    texture.image = image
    nodes.active = texture
    texture.select = True
    select_only(body)
    bpy.ops.object.bake(type="DIFFUSE", pass_filter={"COLOR"}, margin=16)
    for link in list(principled.inputs["Base Color"].links):
        links.remove(link)
    links.new(texture.outputs["Color"], principled.inputs["Base Color"])
    image.pack()
    print(f"BAKED {body.name} 1024x1024", flush=True)


def convert_number_fonts():
    for obj in list(bpy.context.scene.objects):
        is_number = obj.type == "FONT"
        is_edge_divider = obj.type == "CURVE" and "Galaxy_Clean_Edge_Divider_v6" in obj.name
        if not is_number and not is_edge_divider:
            continue
        select_only(obj)
        bpy.ops.object.convert(target="MESH")


def number_belongs_to(obj, prefix):
    if prefix == "D20":
        return obj.name.startswith("Face_")
    return obj.name.startswith(prefix + "_Face_")


def object_belongs_to_export(obj, prefix, body):
    if obj == body or number_belongs_to(obj, prefix):
        return True
    return obj.name.startswith(prefix + "_") and any(token in obj.name for token in (
        "Clustered_StarDust_v5",
        "Galaxy_Clean_Edge_Divider_v6",
    ))


def export_die(output_dir, prefix, body):
    bpy.ops.object.select_all(action="DESELECT")
    selected = []
    for obj in bpy.context.scene.objects:
        if not object_belongs_to_export(obj, prefix, body):
            continue
        obj.hide_set(False)
        obj.hide_viewport = False
        obj.hide_render = False
        obj.select_set(True)
        selected.append(obj)
    if not selected:
        raise RuntimeError(f"No objects selected for {prefix}")
    bpy.context.view_layer.objects.active = body
    output_path = os.path.join(output_dir, EXPORTS[prefix])
    bpy.ops.export_scene.gltf(
        filepath=output_path,
        export_format="GLB",
        use_selection=True,
        export_materials="EXPORT",
        export_image_format="AUTO",
        export_apply=True,
        export_extras=True,
        export_cameras=False,
        export_lights=False,
    )
    print(f"EXPORTED {output_path} OBJECTS={len(selected)}", flush=True)


def main():
    output_dir = output_directory()
    os.makedirs(output_dir, exist_ok=True)
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 4
    scene.render.bake.use_clear = True
    scene.render.bake.use_pass_direct = False
    scene.render.bake.use_pass_indirect = False
    scene.render.bake.use_pass_color = True

    convert_number_fonts()
    bodies = sorted((obj for obj in scene.objects if "_Body_" in obj.name), key=lambda obj: obj.name)
    if len(bodies) != 7:
        raise RuntimeError(f"Expected 7 dice bodies, found {len(bodies)}")
    for body in bodies:
        prefix = body.name.split("_Body_")[0]
        material = gradient_material(f"Galaxy_Web_Baked_{prefix}")
        bake_body(body, material)

    for body in bodies:
        prefix = body.name.split("_Body_")[0]
        export_die(output_dir, prefix, body)


main()
