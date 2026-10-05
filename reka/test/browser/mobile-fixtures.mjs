const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright')
export async function mobilePage({ width = 390, loggedIn = true, closed = false, waitingDice = false } = {}) {
const browser=await chromium.launch({channel:'chrome',headless:true})
const page=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true})
page.setDefaultTimeout(8000); const requests=[]; const errors=[]; page.on('pageerror',e=>errors.push(e.message))
const world={id:1,worldId:10,name:'雾港调查局',myWorld:true,favorSystemStatus:'NORMAL'}
const template={id:10,name:'雾港调查局',authorId:1,background:'沿着雨中的街道，寻找失踪的档案。',visible:true}
const character={characterId:7,userWorldId:1,characterName:'林遥',favorValue:65,userInfoPrompt:'旅途中的同伴'}
const module={id:20,name:'钟楼迷雾',introduction:'调查钟楼的秘密。',ownerUserId:1,visible:true,era:'1920s',playerCount:'2–4 人'}
const conversation={id:30,userWorldId:1,worldId:10,title:'雨夜调查',mode:'chat',status:closed?'closed':'active',characterIds:[7]}
const trpg={...conversation,id:31,title:'钟楼跑团',mode:'trpg',moduleId:20}
const detail={module,context:{truthBackground:'秘密'},locations:[{id:1,name:'钟楼',summary:'雾中的钟楼',content:'线索'.repeat(1000)}],clues:[],materials:[],characters:[]}
if (loggedIn) await page.addInitScript(()=>{localStorage.setItem('galchat.token','mobile-audit');localStorage.setItem('galchat.userId','1');localStorage.setItem('galchat.username','测试旅人')})
await page.route('**/*',async route=>{
 const u=new URL(route.request().url());let p=u.pathname; if(!p.startsWith('/api/')) return route.continue();p=p.slice(4); requests.push({path:p,method:route.request().method()})
 if(p==='/user/login') { await new Promise(resolve=>setTimeout(resolve,1800)); return route.fulfill({json:{code:0,msg:'测试登录失败'}}) }
 let data=[]
 if(p==='/user/info') data={id:1,username:'测试旅人',email:'audit@example.test',diceSkin:'classic'}
 else if(p==='/world/user/1') data=[world]
 else if(p==='/world/1') data=world
 else if(p==='/world/templates') data=[template]
 else if(p==='/world/templates/10') data=template
 else if(p.endsWith('/usage')) data={deletable:false,associatedWorldCount:1}
 else if(p==='/character/1') data=[character]
 else if(p==='/character/templates/10') data=[{id:7,name:'林遥',background:'记者'},{id:8,name:'陈默',background:'医生'}]
 else if(p==='/world/templates/10/details') data=[{id:1,about:'城市传闻',details:'钟声响起，雾便降临。'}]
 else if(p.startsWith('/character-cards/70') || p.startsWith('/character-cards/71')) data={character:{id:70,name:'旅人',actorType:'PLAYER',str:50,con:50,siz:50,dex:50,app:50,int:50,pow:50,edu:50},skills:[],weapons:[]}
 else if(p.endsWith('/context-window') || p.startsWith('/trpg-saves/')) data=null
 else if(p.startsWith('/world-saves/')) data=null
 else if(p==='/coc-modules'||p==='/coc-modules/mine') data=[module]
 else if(p==='/coc-modules/20/manage') data=detail
 else if(p==='/group-chat/conversations') data=[conversation,trpg]
 else if(p==='/group-chat/conversations/30') data=conversation
 else if(p==='/group-chat/conversations/31') data=trpg
 else if(p.endsWith('/reply-plan')) data=[{id:1,source:p.includes('/31/')?'SCENE':'USER',displayName:'钟楼',items:[{order:1,actorType:'character',actorId:7}]}]
 else if(p.endsWith('/turns/current')) data=waitingDice && p.includes('/31/') ? {turnId:5,status:'waiting_input',waitingForUser:true,inputType:'dice',sceneName:'钟楼',steps:[]} : null
 else if(p==='/character-cards/investigators') data=[{cardId:70,actorType:'PLAYER',name:'旅人',checkValues:{}},{cardId:71,actorType:'BOT',participantId:7,name:'林遥',checkValues:{}}]
 else if(p==='/history/care') data={messages:[],latestId:0}
 else if(p.endsWith('/messages')) data=[{id:100,conversationId:p.includes('/31/')?31:30,speakerType:'character',speakerId:7,content:'钟声已经响过，街角的灯还亮着。',sequenceNo:1,status:'completed'}]
 else if(p==='/history') data=[]
 else if(p.includes('creation-rules')) data=null
 await route.fulfill({json:{code:1,data}})
})
return { browser, page, requests, errors }
}
export const appUrl = process.env.MOBILE_AUDIT_URL || 'http://localhost:5173'
export async function enterWorld(page) { await page.goto(appUrl); await page.getByRole('button',{name:'雾港调查局 进入世界'}).click(); await page.getByRole('button',{name:'世界操作'}).waitFor() }
