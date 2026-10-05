import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
const sourceRoot = new URL('../src/', import.meta.url);
registerHooks({
    resolve(specifier, context, nextResolve) {
        if (specifier.startsWith('@/')) {
            return { url: new URL(`${specifier.slice(2)}.ts`, sourceRoot).href, shortCircuit: true };
        }
        if (context.parentURL?.startsWith(sourceRoot.href)
            && /^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier)) {
            return { url: new URL(`${specifier}.ts`, context.parentURL).href, shortCircuit: true };
        }
        return nextResolve(specifier, context);
    },
    load(url, context, nextLoad) {
        if (!url.endsWith('/api/client.ts'))
            return nextLoad(url, context);
        const source = readFileSync(new URL(url), 'utf8');
        return {
            format: 'module-typescript',
            shortCircuit: true,
            source: source.replace('import.meta.env.VITE_API_BASE_URL', 'undefined'),
        };
    },
});
async function mountDirectChat(acitvePushStatus = false, overrides = {}) {
    const { api } = await import('../src/api/client.ts');
    const { useDirectChat } = await import('../src/composables/useDirectChat.ts');
    const { createCharacterData } = await import('../src/composables/characterData.ts');
    const { computed, createRenderer, defineComponent, h, ref } = await import('vue');
    const worldState = ref({ id: 3, worldId: 2, name: '测试世界', acitvePushStatus });
    const world = computed(() => worldState.value);
    const characters = ref([{ userWorldId: 3, characterId: 7, characterName: '测试角色' }, { userWorldId: 3, characterId: 8, characterName: '角色B' }]);
    let chat;
    const renderer = createRenderer({
        patchProp() { },
        insert(child, parent) {
            const children = (parent.children ||= []);
            children.push(child);
            child.parent = parent;
        },
        remove() { },
        createElement: () => ({}),
        createText: (text) => ({ text }),
        createComment: (text) => ({ text }),
        setText(node, text) { node.text = text; },
        setElementText(node, text) { node.text = text; },
        parentNode: (node) => node.parent,
        nextSibling: () => null,
    });
    const app = renderer.createApp(defineComponent({
        setup() {
            const saveCharacterModel = overrides.saveCharacterModel ?? createCharacterData({ worldId: computed(() => world.value.id), sessionKey: () => '', characters, templates: ref([]) }).saveModel;
            chat = useDirectChat({ world, characters, saveCharacterModel, reloadCharacters: async () => undefined, ...overrides });
            return () => h('div');
        },
    }));
    app.mount({});
    return { api, chat, app, characters, worldState };
}
function storage() {
    const data = new Map();
    return { getItem: (key) => data.get(key) ?? null, setItem: (key, value) => { data.set(key, value); }, removeItem: (key) => { data.delete(key); }, key: (i) => [...data.keys()][i] ?? null, get length() { return data.size; } };
}
function event(controller, type, content, sequence, errorDetail) {
    controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify({ type, content, sequence, errorDetail })}\n\n`));
}
async function settle() { for (let i = 0; i < 12; i++)
    await new Promise(resolve => setImmediate(resolve)); }
async function mountWorkspace(acitvePushStatus = false) {
    const { api } = await import('../src/api/client.ts');
    const { useWorkspace } = await import('../src/composables/useWorkspace.ts');
    const { computed, createRenderer, defineComponent, h, ref } = await import('vue');
    const worldState = ref({ id: 3, worldId: 2, name: '测试世界', acitvePushStatus });
    const world = computed(() => worldState.value);
    const characters = ref([{ userWorldId: 3, characterId: 7, characterName: '测试角色' }, { userWorldId: 3, characterId: 8, characterName: '角色B' }]);
    let chat;
    const renderer = createRenderer({
        patchProp() { },
        insert(child, parent) {
            const children = (parent.children ||= []);
            children.push(child);
            child.parent = parent;
        },
        remove() { },
        createElement: () => ({}),
        createText: (text) => ({ text }),
        createComment: (text) => ({ text }),
        setText(node, text) { node.text = text; },
        setElementText(node, text) { node.text = text; },
        parentNode: (node) => node.parent,
        nextSibling: () => null,
    });
    const app = renderer.createApp(defineComponent({
        setup() {
            chat = useWorkspace();
            return () => h('div');
        },
    }));
    app.mount({});
    return { api, chat, app, characters, worldState };
}
Object.assign(globalThis, { localStorage: storage(), sessionStorage: storage(), window: { setTimeout, clearTimeout, addEventListener() { }, removeEventListener() { } } });
async function fixture(t) {
    const { api, chat: workspace, app } = await mountWorkspace();
    const { notice } = await import('../src/composables/useNotice.ts');
    t.after(() => { app.unmount(); clearTimeout(notice.timer); });
    workspace.worlds.value = [{ id: 3, worldId: 2, myWorld: true, name: 'A' }, { id: 4, worldId: 2, myWorld: true, name: 'B' }];
    workspace.selectedWorldId.value = 3;
    workspace.characters.value = [{ userWorldId: 3, characterId: 7, characterName: 'A角色', favorValue: 10, modelApiId: 4 }];
    t.mock.method(api, 'userWorld', async (id) => ({ id, worldId: 2, myWorld: true }));
    t.mock.method(api, 'characters', async (id) => [{ userWorldId: id, characterId: 7, characterName: `world-${id}`, favorValue: 20, modelApiId: 4 }]);
    t.mock.method(api, 'conversations', async () => []);
    t.mock.method(api, 'worldSave', async () => null);
    t.mock.method(api, 'characterTemplates', async (id) => [{ id: id * 10, worldId: id, name: `template-${id}` }]);
    t.mock.method(api, 'worldDetails', async () => []);
    return { api, workspace, app };
}
test('addCharacter keeps its prompt write in the original world', async (t) => {
    const { api, workspace } = await fixture(t);
    let finish;
    const writes = [];
    t.mock.method(api, 'addCharacter', async (world, id) => {
        writes.push(['add', world, id]);
        await new Promise(resolve => { finish = resolve; });
    });
    t.mock.method(api, 'updatePrompt', async (world, id, text) => { writes.push(['prompt', world, id, text]); });
    const adding = workspace.addCharacter(7, 'A专属备注');
    await workspace.selectWorld(4);
    finish();
    await adding;
    assert.deepEqual(writes, [['add', 3, 7], ['prompt', 3, 7, 'A专属备注']]);
});
for (const action of ['world', 'reenter', 'logout'])
    test(`remove list stays scoped after ${action}`, async (t) => {
        const { api, workspace } = await fixture(t);
        let finish;
        let first = true;
        t.mock.method(api, 'deleteCharacter', async () => { });
        t.mock.method(api, 'characters', async (id) => {
            if (first) {
                first = false;
                await new Promise(resolve => { finish = resolve; });
                return [{ userWorldId: id, characterId: 8, characterName: 'old' }];
            }
            return [{ userWorldId: id, characterId: 9, characterName: 'current' }];
        });
        const removing = workspace.removeCharacter(7);
        await settle();
        if (action === 'logout')
            workspace.logout();
        else {
            await workspace.selectWorld(4);
            if (action === 'reenter')
                await workspace.selectWorld(3);
        }
        finish();
        await removing;
        assert.deepEqual(workspace.characters.value.map(c => [c.userWorldId, c.characterId]), action === 'logout' ? [] : [[action === 'world' ? 4 : 3, 9]]);
    });
for (const action of ['refresh', 'reenter'])
    test(`favor edit does not overwrite newer ${action}`, async (t) => {
        const { api, workspace } = await fixture(t);
        t.mock.method(api, 'updateFavor', async () => { });
        let finish;
        let first = true;
        t.mock.method(api, 'characters', async (id) => {
            if (first) {
                first = false;
                await new Promise(resolve => { finish = resolve; });
                return [{ userWorldId: id, characterId: 7, characterName: 'old', favorValue: 15 }];
            }
            return [{ userWorldId: id, characterId: 7, characterName: 'new', favorValue: 25 }];
        });
        const saving = workspace.updateCharacterFavor(7, 15);
        await settle();
        if (action === 'refresh')
            await workspace.reloadCharacters();
        else {
            await workspace.selectWorld(4);
            await workspace.selectWorld(3);
        }
        finish();
        await saving;
        assert.equal(workspace.characters.value[0].favorValue, 25);
    });
test('world initialization preserves a newer character refresh', async (t) => {
    const { api, workspace } = await fixture(t);
    let finish;
    let calls = 0;
    t.mock.method(api, 'userWorld', () => new Promise(resolve => { finish = resolve; }));
    t.mock.method(api, 'characters', async (id) => [{ userWorldId: id, characterId: 7, characterName: 'A', favorValue: ++calls === 1 ? 10 : 20 }]);
    const entering = workspace.selectWorld(3);
    await workspace.reloadCharacters();
    finish({ id: 3 });
    await entering;
    assert.equal(workspace.characters.value[0].favorValue, 20);
});
for (const fails of [false, true])
    test(`world switch stops exposing previous-world characters while loading (fails=${fails})`, async (t) => {
        const { api, workspace } = await fixture(t);
        let finish;
        t.mock.method(api, 'userWorld', async () => { await new Promise(resolve => { finish = resolve; }); if (fails)
            throw new Error('load failed'); return { id: 4 }; });
        const entering = workspace.selectWorld(4);
        const during = workspace.characters.value.map(c => c.userWorldId);
        finish();
        await entering;
        if (!fails)
            assert.ok(during.every(id => id === 4), 'world B still exposes world A characters during load');
        else
            assert.ok(workspace.characters.value.every(c => c.userWorldId === 4), 'failed world load leaves world A characters in B');
    });
test('an older character fetch does not undo a successful model selection', async (t) => {
    const { api, workspace } = await fixture(t);
    const { chat, app } = await mountDirectChat(false, { world: workspace.selectedWorld, characters: workspace.characters, reloadCharacters: workspace.reloadCharacters, saveCharacterModel: workspace.saveCharacterModel });
    t.after(() => app.unmount());
    t.mock.method(api, 'history', async () => []);
    t.mock.method(api, 'modelApis', async () => []);
    t.mock.method(api, 'updateCharacterModel', async () => ({ modelApiId: 99, modelApiName: 'new', modelApiAvailable: true }));
    let finish;
    t.mock.method(api, 'characters', async () => { await new Promise(resolve => { finish = resolve; }); return [{ userWorldId: 3, characterId: 7, characterName: 'A', modelApiId: 4 }]; });
    await chat.selectCharacter(7);
    const refreshing = workspace.reloadCharacters();
    await chat.selectModel(99);
    assert.equal(workspace.characters.value[0].modelApiId, 99);
    finish();
    await refreshing;
    assert.equal(workspace.characters.value[0].modelApiId, 99);
});
test('invalidated model response cannot overwrite a newer model save after world restore', async (t) => {
    const { api, chat, app, characters } = await mountDirectChat();
    const { notice } = await import('../src/composables/useNotice.ts');
    t.after(() => { app.unmount(); clearTimeout(notice.timer); });
    t.mock.method(api, 'history', async () => []);
    t.mock.method(api, 'modelApis', async () => []);
    let finish;
    t.mock.method(api, 'updateCharacterModel', (_w, _c, id) => id === 99 ? new Promise(resolve => { finish = resolve; }) : Promise.resolve({ modelApiId: id, modelApiName: 'new save', modelApiAvailable: true }));
    await chat.selectCharacter(7);
    const saving = chat.selectModel(99);
    chat.invalidateWorld(3);
    characters.value = [{ userWorldId: 3, characterId: 7, characterName: 'restored', modelApiId: 4 }];
    await chat.selectCharacter(7);
    await chat.selectModel(4);
    finish({ modelApiId: 99, modelApiName: 'old save', modelApiAvailable: true });
    await saving;
    assert.equal(characters.value[0].modelApiId, 4);
});
for (const mode of ['create', 'edit'])
    test(`template ${mode} does not overwrite another world's templates`, async (t) => {
        const { api, workspace } = await fixture(t);
        let finish;
        let first = true;
        t.mock.method(api, 'createCharacterTemplate', async () => { });
        t.mock.method(api, 'updateCharacterTemplate', async () => { });
        t.mock.method(api, 'characterTemplates', async (id) => {
            if (first) {
                first = false;
                await new Promise(resolve => { finish = resolve; });
                return [{ id: 21, worldId: 2, name: 'A旧模板' }];
            }
            return [{ id: 31, worldId: 3, name: 'B模板' }];
        });
        const saving = mode === 'create' ? workspace.createCharacterTemplate({ name: 'new' }) : workspace.updateCharacterTemplate(7, { name: 'edited' });
        await settle();
        t.mock.method(api, 'userWorld', async () => ({ id: 4, worldId: 3, myWorld: true }));
        await workspace.selectWorld(4);
        finish();
        await saving;
        assert.equal(workspace.characterTemplates.value[0].worldId, 3);
    });
test('background reply completion refreshes characters in the same world', async (t) => {
    const { api, workspace } = await fixture(t);
    workspace.characters.value.push({ userWorldId: 3, characterId: 8, characterName: 'B' });
    const { chat, app } = await mountDirectChat(false, { world: workspace.selectedWorld, characters: workspace.characters, reloadCharacters: workspace.reloadCharacters, saveCharacterModel: workspace.saveCharacterModel });
    t.after(() => app.unmount());
    t.mock.method(api, 'history', async () => []);
    t.mock.method(api, 'modelApis', async () => []);
    let controller;
    t.mock.method(globalThis, 'fetch', async () => new Response(new ReadableStream({ start(c) { controller = c; } })));
    await chat.selectCharacter(7);
    chat.input.value = 'question';
    const sending = chat.send();
    await settle();
    await chat.selectCharacter(8);
    event(controller, 'generation.completed', '', 1);
    controller.close();
    await sending;
    await chat.selectCharacter(7);
    assert.equal(workspace.characters.value[0].favorValue, 20);
});
test('logout clears character template state', async (t) => {
    const { workspace } = await fixture(t);
    workspace.characterTemplates.value = [{ id: 21, worldId: 2, name: 'private old account template' }];
    workspace.logout();
    assert.deepEqual(workspace.characterTemplates.value, []);
});
test('an old template load does not open its editor in a new world', async (t) => {
    const { api, workspace } = await fixture(t);
    let finish;
    t.mock.method(api, 'myCharacterTemplate', () => new Promise(resolve => { finish = resolve; }));
    const source = readFileSync(new URL('App.vue', sourceRoot), 'utf8');
    const body = source.split('async function openEditCharacterTemplate(id: number) {')[1].split('\nfunction addFavorabilityRow')[0].replace(/}\s*$/, '');
    const AsyncFunction = Object.getPrototypeOf(async function () { }).constructor;
    const open = new AsyncFunction('id', 'run', 'characterTemplateMode', 'editingCharacterTemplateId', 'fillCharacterTemplateForm', 'workspace', 'dialogs', body);
    const dialogs = { characterTemplate: false, characterEdit: false };
    let filled;
    const loading = open(7, async (action) => action(), { value: '' }, { value: null }, data => { filled = data; }, workspace, dialogs);
    await workspace.selectWorld(4);
    finish({ id: 7, worldId: 2, name: 'A editing data' });
    await loading;
    assert.equal(dialogs.characterTemplate, false, 'old A request opens the editor after navigation to B');
});
// Positive controls for the protection already added in previous fixes.
test('control: latest reloadCharacters request wins', async (t) => {
    const { api, workspace } = await fixture(t);
    let finish;
    let first = true;
    t.mock.method(api, 'characters', async () => { if (first) {
        first = false;
        await new Promise(resolve => { finish = resolve; });
        return [{ userWorldId: 3, characterId: 7, characterName: 'old' }];
    } return [{ userWorldId: 3, characterId: 7, characterName: 'new' }]; });
    const old = workspace.reloadCharacters();
    await workspace.reloadCharacters();
    finish();
    await old;
    assert.equal(workspace.characters.value[0].characterName, 'new');
});
test('control: reloadCharacters does not overwrite a different world', async (t) => {
    const { api, workspace } = await fixture(t);
    let finish;
    t.mock.method(api, 'characters', async (id) => { if (id === 3)
        await new Promise(resolve => { finish = resolve; }); return [{ userWorldId: id, characterId: 7, characterName: 'character' }]; });
    const old = workspace.reloadCharacters();
    await workspace.selectWorld(4);
    finish();
    await old;
    assert.equal(workspace.characters.value[0].userWorldId, 4);
});
test('an older refresh cannot resurrect a removed character', async (t) => {
    const { api, workspace } = await fixture(t);
    let finish;
    let first = true;
    t.mock.method(api, 'deleteCharacter', async () => { });
    t.mock.method(api, 'characters', async () => { if (first) {
        first = false;
        await new Promise(resolve => { finish = resolve; });
        return [{ userWorldId: 3, characterId: 7, characterName: 'deleted' }];
    } return []; });
    const old = workspace.reloadCharacters();
    await workspace.removeCharacter(7);
    assert.deepEqual(workspace.characters.value, []);
    finish();
    await old;
    assert.deepEqual(workspace.characters.value, []);
});
test('background withdrawal refreshes favor for its original character', async (t) => {
    const { api, workspace } = await fixture(t);
    workspace.characters.value.push({ userWorldId: 3, characterId: 8, characterName: 'B' });
    const { chat, app } = await mountDirectChat(false, { world: workspace.selectedWorld, characters: workspace.characters, reloadCharacters: workspace.reloadCharacters, saveCharacterModel: workspace.saveCharacterModel });
    t.after(() => app.unmount());
    let withdrawn = false;
    let finish;
    t.mock.method(api, 'history', async (_world, character) => character === 7 && !withdrawn ? [{ id: 20, type: 'user', content: 'question' }] : []);
    t.mock.method(api, 'modelApis', async () => []);
    t.mock.method(api, 'withdrawMessage', async () => { await new Promise(resolve => { finish = resolve; }); withdrawn = true; });
    await chat.selectCharacter(7);
    const withdrawing = chat.withdraw();
    await chat.selectCharacter(8);
    finish();
    await withdrawing;
    await chat.selectCharacter(7);
    assert.equal(workspace.characters.value[0].favorValue, 20);
});


test('saving only a note does not resave an unchanged unavailable model', async t => {
  const { api, workspace } = await fixture(t)
  t.mock.method(api, 'updatePrompt', async () => {})
  t.mock.method(api, 'updateCharacterModel', async () => { throw new Error('已删除的模型不能重新绑定') })
  await workspace.updateCharacterSettings(3, 7, { userInfoPrompt: '新备注', modelApiId: 4 })
})

test('a partially successful settings save reloads authoritative character data', async t => {
  const { api, workspace } = await fixture(t)
  t.mock.method(api, 'updatePrompt', async () => {})
  t.mock.method(api, 'updateCharacterModel', async () => { throw new Error('模型保存失败') })
  t.mock.method(api, 'characters', async () => [{ userWorldId: 3, characterId: 7, characterName: 'A', userInfoPrompt: '已保存的备注', modelApiId: 4 }])
  await assert.rejects(workspace.updateCharacterSettings(3, 7, { userInfoPrompt: '已保存的备注', modelApiId: 99 }), /模型保存失败/)
  assert.equal(workspace.characters.value[0].userInfoPrompt, '已保存的备注')
  assert.equal(workspace.characters.value[0].modelApiId, 4)
})

test('unmount invalidates character requests still in flight', async t => {
  const { api, workspace, app } = await fixture(t)
  let finish
  t.mock.method(api, 'characters', () => new Promise(resolve => { finish = resolve }))
  const reading = workspace.reloadCharacters()
  app.unmount()
  finish([{ userWorldId: 3, characterId: 7, characterName: 'late' }])
  await reading
  assert.ok(!workspace.characters.value.some(character => character.characterName === 'late'))
})

test('logout prevents the second write of a pending character addition', async t => {
  const { api, workspace } = await fixture(t)
  let finish
  t.mock.method(api, 'addCharacter', () => new Promise(resolve => { finish = resolve }))
  const prompts = []
  t.mock.method(api, 'updatePrompt', async (...args) => { prompts.push(args) })
  const adding = workspace.addCharacter(7, '旧账号的备注')
  workspace.logout()
  finish(); await adding
  assert.deepEqual(prompts, [])
  assert.deepEqual(workspace.characters.value, [])
})

test('model refresh updates active and cached direct chats and ignores their older reads', async t => {
    const {api,workspace}=await fixture(t)
    const {chat,app}=await mountDirectChat(false,{world:workspace.selectedWorld,characters:workspace.characters,reloadCharacters:workspace.reloadCharacters,saveCharacterModel:workspace.saveCharacterModel})
    t.after(()=>app.unmount())
    t.mock.method(api,'history',async()=>[])
    workspace.characters.value.push({userWorldId:3,characterId:8,characterName:'B角色'})
    const pending=[]
    t.mock.method(api,'modelApis',()=>new Promise(resolve=>pending.push(resolve)))
    await chat.selectCharacter(7)
    await chat.selectCharacter(8)
    t.mock.method(api,'modelApis',async()=>[{id:99,name:'new model'}])
    await chat.refreshModelApis()
    assert.equal(chat.modelApis.value[0].id,99)
    for (const finish of pending) finish([])
    await settle()
    assert.equal(chat.modelApis.value[0].id,99)
    await chat.selectCharacter(7)
    assert.equal(chat.modelApis.value[0].id,99)
})
