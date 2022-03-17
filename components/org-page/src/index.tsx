import * as React from 'react'
import styles from './styles.module.css'

const logoImg = '/assets/org-page/img/logo.png'
const peopleImg = '/assets/org-page/img/people.png'
const pinnedImg = '/assets/org-page/img/pinned.png'
const projectImg = '/assets/org-page/img/project.png'
const urlImg = '/assets/org-page/img/url.png'
const userImg = '/assets/org-page/img/user.png'

export interface ProjectProps {
  status?: string
  title?: string
  isPublic?: boolean
  description?: string
  members?: number
  projectId?: string
  link?: string
  createdAt?: string
  lastAtive?: string
  pinned?: boolean
}

export interface MemberProps {
  name?: string
  userId?: number
  url?: string
}

export interface TabProps {
  projects?: ProjectProps[]
  members?: MemberProps[]
}

enum TabType {
  Projects = 'projects',
  Members = 'Members'
}

const Tab = (props: TabProps) => {
  const [activeTab, setActiveTab] = React.useState<TabType>(TabType.Projects)

  const setTab = (tab: TabType) => {
    setActiveTab(tab)
  }

  return (
    <div className={styles.body}>
      <div className={styles.table}>
        <div className={styles.topsection}>
          <div className={styles.logo}>
            <img src={logoImg} alt="" />
          </div>
          <div className={styles.infos}>
            <p className={styles.tit}>
              ontox
            </p>
            <p>
              <img src={urlImg} alt="" />
              <a href="https://ontox-project.eu/" target="_blank">
                https://ontox-project.eu/
              </a>
            </p>
          </div>
        </div>

        <div className={styles.tabs}>
          <a onClick={() => setTab(TabType.Projects)} className={[styles.projects_table_a, activeTab === TabType.Projects ? styles.activea : {}].join(' ')}><img src={projectImg} /> Projects</a>
          <a onClick={() => setTab(TabType.Members)} className={[styles.members_table_a, activeTab === TabType.Projects ? styles.activea : {}].join(' ')}><img src={peopleImg} alt="" /> Members</a>
        </div>

        <div className={styles.tables}>
          {activeTab === TabType.Projects &&
            <div className={[styles.projects_table, styles.active_table].join(' ')}>
              {(props.projects ?? []).map((item) => (
                <div className={styles.row_t} key={item.projectId}>
                  <h1>{`${item.status}: ${item.title} `}{item.isPublic && <span>Public</span>}</h1>
                  {item.pinned && <img src={pinnedImg} className={[styles.pinned, styles.pinned_active].join(' ')} alt="" />}
                  <p>{item.description}</p>
                  <div className={styles.datas}>
                    <p><img src={userImg} alt="" /> {item.members}</p>
                    <p className={styles.withoutimg}><span>Project ID: </span> {item.projectId}</p>
                    <p><img src={urlImg} alt="" /> <a href={item.link}
                      target="_blank">{item.link}</a></p>
                  </div>
                  <div className={styles.datas2}>
                    <p className={styles.withoutimg}><span>Created at: </span> {item.createdAt}</p>
                    <p className={styles.withoutimg}><span>Last active: </span> {item.lastAtive}</p>
                  </div>
                </div>
              ))}
            </div>
          }
          {
            activeTab === TabType.Members &&
            <div className={[styles.members_table, styles.active_table].join(' ')}>
              <table>
                <tr>
                  <th>Name</th>
                  <th>User ID</th>
                  <th>URL</th>
                </tr>
                {
                  (props.members ?? []).map((item) => (
                    <tr key={item.userId}>
                      <td>{item.name}</td>
                      <td>{item.userId}</td>
                      <td><a href={item.url} target="_blank">{item.url}</a>
                      </td>
                    </tr>
                  ))
                }
              </table>
            </div>
          }
        </div>
      </div>
    </div>
  )
}

export default {
  styles: styles,
  Tab: Tab
}