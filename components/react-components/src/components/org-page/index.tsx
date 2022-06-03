import * as React from 'react'
import styles from './styles.module.css'
import MenuButton from './components/menu-button'

const peopleImg = '/assets/org-page/img/people.png'
const pinnedImg = '/assets/org-page/img/pinned.png'
const projectImg = '/assets/org-page/img/project.png'
const urlImg = '/assets/org-page/img/url.png'
const userImg = '/assets/org-page/img/user.png'

export interface ProjectProps {
  title?: string
  isPublic?: boolean
  descriptionHtml?: string
  members?: number
  projectId?: string
  link?: string
  createdAt?: string
  lastActive?: string
  pinned?: boolean
  status?: string
}

export interface MemberProps {
  name?: string
  userId: number
  url?: string
}

export interface TabProps {
  darkMode?: boolean
  title: string
  url?: string
  logoImgUrl?: string
  projects?: ProjectProps[]
  members?: MemberProps[]
  userIsAdmin?: boolean
  orgId?: number
  addMember?: () => void
  inviteUrl?: () => void
  changeRole: (user: MemberProps) => void
  removeFromOrganization: (user: MemberProps) => void
}

enum TabType {
  Projects = 'projects',
  Members = 'users'
}

const Tab = (props: TabProps) => {

  const initialState = window.location.pathname.split("/").pop() === "projects" ? TabType.Projects : TabType.Members

  const [activeTab, setActiveTab] = React.useState<TabType>(initialState)
  const { title, url, logoImgUrl } = props;
  const isAdmin = (props.userIsAdmin ?? false)

  const setUrlPath = (tab: TabType): void => {
    // updates the browser url without reloading the page
    const url = window.location.href.replace(/\/[^\/]*$/, `/${tab}`);
    history.pushState({}, '', url);
  }

  const setTab = (tab: TabType): void => {
    setUrlPath(tab);
    setActiveTab(tab);
  }

  return (
    <div className={[styles.body, (props.darkMode ?? false) ? styles.darkmode : {}].join(' ')}>
      <div className={styles.table}>
        <div className={styles.topsection}>
          {logoImgUrl
            ? <div className={styles.logo}>
              <img src={logoImgUrl} alt="" width="100%" />
            </div>
            : null
          }
          <div className={styles.infos}>
            <p className={styles.tit}>
              {title}
            </p>
            <p>
              {url
                ? <div>
                  <img src={urlImg} alt="" />
                  <a href={url} target="_blank">
                    {url}
                  </a>
                </div>
                : null
              }
            </p>
          </div>
        </div>

        {isAdmin &&
          <div className={styles.adminButtons}>
            {activeTab === TabType.Projects
              ? <a className={`ui positive button ${styles.darkModeOverride}`} href={`/new?project_owner=${props.orgId}`}>New</a>
              :
              <div>
                <a className={`ui positive button ${styles.darkModeOverride}`} onClick={() => props.addMember?.()}>Add Member</a>
                <a className="ui button org-invite-url-button" onClick={() => props.inviteUrl?.()}>Invite URL</a>
              </div>
            }
          </div>
        }

        <div className={styles.tabs}>
          <a onClick={() => setTab(TabType.Projects)} className={[styles.projects_table_a, activeTab === TabType.Projects ? styles.activea : {}].join(' ')}><img src={projectImg} /> Projects</a>
          <a onClick={() => setTab(TabType.Members)} className={[styles.members_table_a, activeTab === TabType.Projects ? styles.activea : {}].join(' ')}><img src={peopleImg} alt="" /> Members</a>
        </div>

        <div className={styles.tables}>
          {activeTab === TabType.Projects &&
            <div className={[styles.projects_table, styles.active_table].join(' ')}>
              {(props.projects ?? []).map((item) => (
                <div className={styles.row_t} key={item.projectId}>
                  <h1>{item.title}{item.isPublic && <span>Public</span>}</h1>
                  {item.pinned && <img src={pinnedImg} className={[styles.pinned, styles.pinned_active].join(' ')} alt="" />}
                  <div className={styles.datas}>
                    <p><img src={userImg} alt="" /> {item.members}</p>
                    <p className={styles.withoutimg}><span>Project ID: </span> {item.projectId}</p>
                    <p><img src={urlImg} alt="" /> <a href={item.link}
                      target="_blank">{item.link}</a></p>
                  </div>
                  <div className={styles.datas2}>
                    {item.createdAt && <p className={styles.withoutimg}><span>Created at: </span> {item.createdAt}</p>}
                    {item.lastActive && <p className={styles.withoutimg}><span>Last active: </span> {item.lastActive}</p>}
                  </div>
                </div>
              ))}
            </div>
          }
          {
            activeTab === TabType.Members &&
            <div className={[styles.members_table, styles.active_table].join(' ')}>
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>User ID</th>
                    <th>URL</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {
                    (props.members ?? []).map((item) => (
                      <tr key={item.userId}>
                        <td>{item.name}</td>
                        <td>{item.userId}</td>
                        <td><a href={item.url} target="_blank">{item.url}</a>
                        </td>
                        {
                          isAdmin &&
                          <td style={{ width: '50px' }}>
                            <MenuButton
                              buttons={[
                                { title: 'Change Role', onClick: () => props.changeRole(item) },
                                { title: 'Remove From Organization', onClick: () => props.removeFromOrganization(item)}
                              ]}
                            />
                          </td>
                        }
                      </tr>
                    ))
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      </div>
    </div>
  )
}

export default Tab
